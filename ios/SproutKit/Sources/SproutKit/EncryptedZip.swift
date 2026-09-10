import CommonCrypto
import CryptoKit
import Foundation

/// A zip archive encrypted with AES-256, in the WinZip AE-2 scheme.
/// From `data/export/EncryptedZip.kt`.
///
/// This exists because a report can end up sitting in a chat thread or an inbox
/// for years (BDR-0013), and the only thing that helps there is real encryption
/// of the file itself.
///
/// **The legacy ZipCrypto scheme is deliberately not implemented.** It is the one
/// Windows Explorer and macOS open by double-click, which makes it tempting, and
/// it is broken: a known-plaintext attack recovers the contents in seconds, and
/// an archive of a PDF and an `.xlsx` hands an attacker exactly the known
/// plaintext it needs — both formats begin with fixed bytes. Shipping it on a
/// baby's health record would be theatre. It is **not a fallback and not an
/// option**; the cost of the sound choice is real and is stated in the app: the
/// recipient may need a proper unzip tool.
///
/// No dependency, per ADR-0013. Every primitive is a system one — CryptoKit for
/// HMAC-SHA1, CommonCrypto for the AES block, both part of the OS the way
/// `javax.crypto` is on the other side. The key derivation is written out rather
/// than taken from a provider, for the reason the Kotlin gives: implementations
/// have disagreed about how a password's characters become bytes, and a
/// disagreement there produces an archive that simply will not open — discovered
/// by the person on the other end, not by us.
///
/// ## The format, for whoever reads this next
///
/// Per entry, the encrypted payload is
/// `salt (16) | password verification (2) | ciphertext | authentication code (10)`,
/// and the entry's headers carry compression method 99 with an extra field
/// (`0x9901`) naming AES-256 and the real compression method underneath. AE-2
/// stores no CRC — the field is zero, and the HMAC is what proves the bytes
/// arrived intact.
public enum EncryptedZip {

    public static let mimeType = "application/zip"
    public static let fileExtension = "zip"

    /// One file to put in the archive.
    public struct Entry {
        public let name: String
        public let bytes: Data

        public init(name: String, bytes: Data) {
            self.name = name
            self.bytes = bytes
        }
    }

    public struct Failure: Error, CustomStringConvertible {
        public let description: String
        init(_ description: String) { self.description = description }
    }

    // AES-256: a 16-byte salt, two 32-byte keys and two verification bytes.
    private static let saltBytes = 16
    private static let keyBytes = 32
    private static let verifierBytes = 2
    private static let authCodeBytes = 10
    private static let iterations = 1000

    private static let aesStrength256: UInt8 = 3
    private static let methodAes: UInt16 = 99
    private static let methodStored: UInt16 = 0
    private static let versionAes: UInt16 = 51
    private static let ae2: UInt16 = 2

    /// `entries` as one archive, each encrypted under `password`.
    ///
    /// Nothing is deflated first: a PDF and an `.xlsx` are already compressed, so
    /// a second pass would spend time to save almost nothing, and "stored" keeps
    /// what happens to the bytes easy to follow.
    ///
    /// `modifiedAt` and `salt` are parameters so a test can pin them; in the app
    /// they are the clock and fresh random bytes.
    public static func archive(
        _ entries: [Entry],
        password: String,
        modifiedAt: Date = Date(),
        salt: @escaping () -> Data = { randomSalt() }
    ) throws -> Data {
        guard !entries.isEmpty else { throw Failure("an archive needs at least one entry") }
        guard !password.isEmpty else { throw Failure("an encrypted archive needs a password") }

        // The password as UTF-8 bytes — what 7-Zip and WinZip encode, and the
        // reason a password with an accent in it opens on the other side.
        let passwordBytes = Data(password.utf8)
        let parts = dosDateTime(modifiedAt)

        var out = Data()
        var central = Data()
        var offset = 0

        for entry in entries {
            let name = Data(entry.name.utf8)
            let payload = try encrypt(entry.bytes, password: passwordBytes, salt: salt())

            let local = localHeader(
                name: name,
                compressed: payload.count,
                uncompressed: entry.bytes.count,
                time: parts.time,
                date: parts.date
            )
            out.append(local)
            out.append(payload)

            central.append(
                centralHeader(
                    name: name,
                    compressed: payload.count,
                    uncompressed: entry.bytes.count,
                    time: parts.time,
                    date: parts.date,
                    offset: offset
                )
            )
            offset += local.count + payload.count
        }

        out.append(central)
        out.append(
            endOfCentralDirectory(
                entries: entries.count,
                directorySize: central.count,
                directoryOffset: offset
            )
        )
        return out
    }

    /// Sixteen bytes from the system's random source.
    public static func randomSalt() -> Data {
        var bytes = [UInt8](repeating: 0, count: saltBytes)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }

    // MARK: - The encrypted payload

    /// `salt | verification | ciphertext | authentication code`.
    ///
    /// The two verification bytes are what lets a reader say "wrong password"
    /// immediately instead of handing back rubbish; they are the tail of the same
    /// derived key material, so they prove knowledge of the password without
    /// revealing it.
    private static func encrypt(_ plain: Data, password: Data, salt: Data) throws -> Data {
        let derived = pbkdf2(
            password: password,
            salt: salt,
            iterations: iterations,
            length: keyBytes * 2 + verifierBytes
        )
        let encryptionKey = derived.prefix(keyBytes)
        let authenticationKey = derived.dropFirst(keyBytes).prefix(keyBytes)
        let verifier = derived.suffix(verifierBytes)

        let cipherText = try counterMode(plain, key: Data(encryptionKey))
        let authCode = Data(
            HMAC<Insecure.SHA1>.authenticationCode(
                for: cipherText,
                using: SymmetricKey(data: Data(authenticationKey))
            )
        ).prefix(authCodeBytes)

        return salt + Data(verifier) + cipherText + Data(authCode)
    }

    /// AES in counter mode, with **WinZip's counter rather than the usual one**.
    ///
    /// The block counter starts at 1 and is incremented as a *little-endian*
    /// integer. Every stock CTR mode — `kCCModeCTR` here, `AES/CTR/NoPadding` on
    /// the other side — counts big-endian, so neither can be used: it would
    /// produce an archive that decrypts to noise everywhere past the first block
    /// boundary. The counter blocks are encrypted with AES-ECB and XORed into the
    /// data, which is what counter mode is.
    private static func counterMode(_ data: Data, key: Data) throws -> Data {
        var counter = [UInt8](repeating: 0, count: 16)
        var block: UInt64 = 0
        var position = 0

        let plain = [UInt8](data)
        var result = [UInt8](repeating: 0, count: data.count)

        while position < plain.count {
            block += 1
            // Little-endian: the low byte first, which is the whole point.
            var value = block
            for i in 0..<8 {
                counter[i] = UInt8(value & 0xFF)
                value >>= 8
            }
            for i in 8..<16 { counter[i] = 0 }

            let keyStream = try aesEcbBlock(counter, key: key)
            let remaining = min(16, plain.count - position)
            for i in 0..<remaining {
                result[position + i] = plain[position + i] ^ keyStream[i]
            }
            position += remaining
        }
        return Data(result)
    }

    /// One AES block, encrypted with no mode and no padding.
    ///
    /// CryptoKit offers only authenticated ciphers, which is the right default
    /// and the wrong tool for building someone else's counter mode — so this is
    /// CommonCrypto, a system framework, and the ECB call is a single block by
    /// construction rather than a mode anything is encrypted *in*.
    private static func aesEcbBlock(_ block: [UInt8], key: Data) throws -> [UInt8] {
        var out = [UInt8](repeating: 0, count: 16)
        var moved = 0
        let status = key.withUnsafeBytes { keyBytes in
            CCCrypt(
                CCOperation(kCCEncrypt),
                CCAlgorithm(kCCAlgorithmAES),
                CCOptions(kCCOptionECBMode),
                keyBytes.baseAddress, key.count,
                nil,
                block, block.count,
                &out, out.count,
                &moved
            )
        }
        guard status == CCCryptorStatus(kCCSuccess), moved == 16 else {
            throw Failure("AES refused a counter block (status \(status))")
        }
        return out
    }

    /// PBKDF2-HMAC-SHA1, written out rather than taken from a provider.
    ///
    /// See the note at the top. The password arrives already encoded, and the
    /// rest is RFC 2898 §5.2 — checked against the RFC 6070 vectors, which is
    /// what makes this the same function the other side computes.
    public static func pbkdf2(
        password: Data,
        salt: Data,
        iterations: Int,
        length: Int
    ) -> Data {
        let key = SymmetricKey(data: password)
        var out = Data()
        var blockIndex: UInt32 = 1

        while out.count < length {
            var seed = salt
            seed.append(contentsOf: [
                UInt8((blockIndex >> 24) & 0xFF),
                UInt8((blockIndex >> 16) & 0xFF),
                UInt8((blockIndex >> 8) & 0xFF),
                UInt8(blockIndex & 0xFF),
            ])

            var u = Data(HMAC<Insecure.SHA1>.authenticationCode(for: seed, using: key))
            var block = u
            if iterations >= 2 {
                for _ in 2...iterations {
                    u = Data(HMAC<Insecure.SHA1>.authenticationCode(for: u, using: key))
                    for i in block.indices { block[i] ^= u[i] }
                }
            }

            out.append(block)
            blockIndex += 1
        }
        return out.prefix(length)
    }

    // MARK: - The archive structure

    private static func localHeader(
        name: Data, compressed: Int, uncompressed: Int, time: UInt16, date: UInt16
    ) -> Data {
        var header = Data()
        header.append(le: UInt32(0x0403_4B50))
        header.append(le: versionAes)
        header.append(le: flags(name))
        header.append(le: methodAes)
        header.append(le: time)
        header.append(le: date)
        header.append(le: UInt32(0))  // AE-2 stores no CRC; the HMAC proves the bytes.
        header.append(le: UInt32(compressed))
        header.append(le: UInt32(uncompressed))
        header.append(le: UInt16(name.count))
        header.append(le: UInt16(aesExtraField.count))
        header.append(name)
        header.append(aesExtraField)
        return header
    }

    private static func centralHeader(
        name: Data, compressed: Int, uncompressed: Int, time: UInt16, date: UInt16, offset: Int
    ) -> Data {
        var header = Data()
        header.append(le: UInt32(0x0201_4B50))
        header.append(le: versionAes)
        header.append(le: versionAes)
        header.append(le: flags(name))
        header.append(le: methodAes)
        header.append(le: time)
        header.append(le: date)
        header.append(le: UInt32(0))
        header.append(le: UInt32(compressed))
        header.append(le: UInt32(uncompressed))
        header.append(le: UInt16(name.count))
        header.append(le: UInt16(aesExtraField.count))
        header.append(le: UInt16(0))  // no comment
        header.append(le: UInt16(0))  // disk 0
        header.append(le: UInt16(0))  // internal attributes
        header.append(le: UInt32(0))  // external attributes
        header.append(le: UInt32(offset))
        header.append(name)
        header.append(aesExtraField)
        return header
    }

    private static func endOfCentralDirectory(
        entries: Int, directorySize: Int, directoryOffset: Int
    ) -> Data {
        var record = Data()
        record.append(le: UInt32(0x0605_4B50))
        record.append(le: UInt16(0))
        record.append(le: UInt16(0))
        record.append(le: UInt16(entries))
        record.append(le: UInt16(entries))
        record.append(le: UInt32(directorySize))
        record.append(le: UInt32(directoryOffset))
        record.append(le: UInt16(0))  // no archive comment
        return record
    }

    /// Bit 0 says the entry is encrypted. Bit 11 says the name is UTF-8, and is
    /// only set when the name actually needs it — an old reader is happier with a
    /// plain ASCII name and no flag it does not understand.
    private static func flags(_ name: Data) -> UInt16 {
        name.allSatisfy { $0 >= 0x20 && $0 <= 0x7E } ? 0x0001 : 0x0801
    }

    /// The AES extra field: AE-2, AES-256, and the compression that was actually
    /// used underneath the encryption.
    private static let aesExtraField: Data = {
        var field = Data()
        field.append(le: UInt16(0x9901))
        field.append(le: UInt16(7))
        field.append(le: ae2)
        field.append(contentsOf: [UInt8(ascii: "A"), UInt8(ascii: "E"), aesStrength256])
        field.append(le: methodStored)
        return field
    }()

    /// MS-DOS date and time, as the zip format stores them.
    ///
    /// Spelled out one named `Int` at a time rather than as two expressions.
    /// `(parts.hour ?? 0) << 11 | …` inside a `UInt16(_:)` is a chain of
    /// defaulted optionals, shifts, ors and an integer conversion, and Swift's
    /// type checker gives up on it — "unable to type-check this expression in
    /// reasonable time", which is a compile error and not a warning.
    private static func dosDateTime(_ at: Date) -> (time: UInt16, date: UInt16) {
        let parts = Calendar(identifier: .gregorian).dateComponents(
            [.year, .month, .day, .hour, .minute, .second], from: at
        )
        let hour: Int = parts.hour ?? 0
        let minute: Int = parts.minute ?? 0
        let second: Int = parts.second ?? 0
        let year: Int = max((parts.year ?? 1980) - 1980, 0)
        let month: Int = parts.month ?? 1
        let dayOfMonth: Int = parts.day ?? 1

        // Two seconds per unit is the format's own resolution, not a rounding
        // choice of ours.
        let packedTime: Int = (hour << 11) | (minute << 5) | (second / 2)
        let packedDate: Int = (year << 9) | (month << 5) | dayOfMonth
        return (UInt16(packedTime), UInt16(packedDate))
    }
}

private extension Data {
    mutating func append(le value: UInt16) {
        append(contentsOf: [UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF)])
    }

    mutating func append(le value: UInt32) {
        append(contentsOf: [
            UInt8(value & 0xFF),
            UInt8((value >> 8) & 0xFF),
            UInt8((value >> 16) & 0xFF),
            UInt8((value >> 24) & 0xFF),
        ])
    }
}
