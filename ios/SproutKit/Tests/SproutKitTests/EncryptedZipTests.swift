import XCTest
@testable import SproutKit

/// The counterpart of `EncryptedZipTest.kt`.
///
/// This is the file BDR-0013 rests on, and the failures it can have are all
/// silent: an archive that nobody can open, or one that opens and should not
/// have. So two things are checked against something outside this code —
///
/// - **the key derivation against the RFC 6070 vectors**, because a PBKDF2 that
///   disagrees by one byte produces an archive with no error message and no way
///   in;
/// - **a whole entry against a vector from a different implementation**, because
///   the counter is WinZip's and not the usual one, and getting that wrong
///   produces bytes that look encrypted and decrypt to noise.
///
/// The structural checks read the archive the way a reader reaches into one:
/// from the end record, into the central directory, out to each local header.
final class EncryptedZipTests: XCTestCase {

    private let password = "Léa-2026 clinic"

    /// A salt of 00..0F, so the payload is reproducible.
    private let fixedSalt: () -> Data = { Data((0..<16).map(UInt8.init)) }

    /// 8 September 2026, 14:30:08 — pinned so the headers are reproducible.
    private var at: Date {
        var parts = DateComponents()
        parts.year = 2026; parts.month = 9; parts.day = 8
        parts.hour = 14; parts.minute = 30; parts.second = 8
        return Calendar(identifier: .gregorian).date(from: parts)!
    }

    private func archive(
        _ entries: [EncryptedZip.Entry],
        secret: String? = nil,
        salt: (() -> Data)? = nil
    ) throws -> Data {
        try EncryptedZip.archive(
            entries,
            password: secret ?? password,
            modifiedAt: at,
            salt: salt ?? fixedSalt
        )
    }

    private func short(_ bytes: Data, _ at: Int) -> Int {
        let low = Int(bytes[at])
        let high = Int(bytes[at + 1])
        return low | (high << 8)
    }

    /// Assembled a byte at a time, for the reason `EncryptedZip.dosDateTime`
    /// carries: four `Int(_:)` conversions chained through shifts and ors is
    /// more than Swift's type checker will work through, and it fails the build
    /// rather than warning.
    private func int(_ bytes: Data, _ at: Int) -> Int {
        var value = 0
        for offset in (0..<4).reversed() {
            value = (value << 8) | Int(bytes[at + offset])
        }
        return value
    }

    private let localHeaderBytes = 30
    private let aesExtraBytes = 11

    // MARK: - Against the outside world

    func testTheKeyDerivationMatchesTheRfc6070Vectors() {
        func derive(_ password: String, _ salt: String, _ iterations: Int, _ length: Int) -> String {
            EncryptedZip.pbkdf2(
                password: Data(password.utf8),
                salt: Data(salt.utf8),
                iterations: iterations,
                length: length
            ).map { String(format: "%02x", $0) }.joined()
        }

        XCTAssertEqual(derive("password", "salt", 1, 20), "0c60c80f961f0e71f3a9b524af6012062fe037a6")
        XCTAssertEqual(derive("password", "salt", 2, 20), "ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957")
        XCTAssertEqual(derive("password", "salt", 4096, 20), "4b007901b765489abead49d926f721d065a429c1")
        XCTAssertEqual(
            derive("passwordPASSWORDpassword", "saltSALTsaltSALTsaltSALTsaltSALTsalt", 4096, 25),
            "3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038"
        )
    }

    /// The bytes of one entry, against a vector from a different implementation.
    ///
    /// The plaintext is deliberately 70 bytes — four whole AES blocks and a
    /// partial one — because that is where the two mistakes worth catching show
    /// up: a counter incremented the wrong way round, and a final block handled
    /// as if it were full.
    func testTheEncryptedPayloadMatchesAnIndependentImplementation() throws {
        let plain = Data((0..<70).map { UInt8(($0 * 7 + 3) % 256) })
        let bytes = try archive([EncryptedZip.Entry(name: "r.bin", bytes: plain)])

        // salt | verifier | ciphertext | authentication code
        let expected = Data(hex:
            "000102030405060708090a0b0c0d0e0f"
            + "b0ba"
            + "a224d656b50ee9ebfeaaca45bca45875571a18b1e0dd192bb43f3a0d7be0411f"
            + "0aada0cd8fb556b2be67696405badcb3eb70b1dd700e846eefa52c4eefd72765"
            + "a822a0ac494e"
            + "44d7adccab5fe8a4cd61"
        )

        let start = localHeaderBytes + "r.bin".count + aesExtraBytes
        XCTAssertEqual(bytes.subdata(in: start..<(start + expected.count)), expected)
    }

    // MARK: - The archive's shape

    func testTheArchiveIsShapedTheWayAReaderExpects() throws {
        let plain = Data((0..<100).map { UInt8($0) })
        let bytes = try archive([EncryptedZip.Entry(name: "report.pdf", bytes: plain)])

        XCTAssertEqual(int(bytes, 0), 0x0403_4B50)
        XCTAssertEqual(short(bytes, 4), 51, "version needed for AES")
        XCTAssertEqual(short(bytes, 6), 0x0001, "encrypted")
        XCTAssertEqual(short(bytes, 8), 99, "AES, not a real compression method")
        XCTAssertEqual(int(bytes, 14), 0, "AE-2 keeps no CRC")
        XCTAssertEqual(int(bytes, 18), 16 + 2 + plain.count + 10, "compressed size")
        XCTAssertEqual(int(bytes, 22), plain.count)
        XCTAssertEqual(short(bytes, 26), "report.pdf".count)
        XCTAssertEqual(short(bytes, 28), aesExtraBytes)

        let extra = localHeaderBytes + "report.pdf".count
        XCTAssertEqual(short(bytes, extra), 0x9901)
        XCTAssertEqual(short(bytes, extra + 2), 7)
        XCTAssertEqual(short(bytes, extra + 4), 2, "AE-2")
        XCTAssertEqual(bytes[extra + 6], UInt8(ascii: "A"))
        XCTAssertEqual(bytes[extra + 7], UInt8(ascii: "E"))
        XCTAssertEqual(bytes[extra + 8], 3, "AES-256")
        XCTAssertEqual(short(bytes, extra + 9), 0, "stored")

        let eocd = bytes.count - 22
        XCTAssertEqual(int(bytes, eocd), 0x0605_4B50)
        XCTAssertEqual(short(bytes, eocd + 8), 1)
        XCTAssertEqual(short(bytes, eocd + 10), 1)
        let directoryAt = int(bytes, eocd + 16)
        XCTAssertEqual(int(bytes, directoryAt), 0x0201_4B50)
        XCTAssertEqual(int(bytes, directoryAt + 42), 0, "first entry's offset")
    }

    /// Both entries are reachable the way a reader reaches them: from the end
    /// record, into the central directory, and out to each local header.
    ///
    /// The walk is written out rather than handed to a zip reader, and on this
    /// side that is not only a preference: Foundation has no zip reader, and the
    /// one thing that would read it — `unzip` — refuses an AES entry without the
    /// password. So the navigation is checked against the format itself.
    func testBothEntriesAreReachableFromTheCentralDirectory() throws {
        let names = ["report.pdf", "data.xlsx"]
        let sizes = [4321, 987]
        let bytes = try archive(
            zip(names, sizes).map {
                EncryptedZip.Entry(name: $0.0, bytes: Data(repeating: 1, count: $0.1))
            }
        )

        let eocd = bytes.count - 22
        XCTAssertEqual(int(bytes, eocd), 0x0605_4B50)
        XCTAssertEqual(short(bytes, eocd + 10), names.count)

        var cursor = int(bytes, eocd + 16)
        for (index, name) in names.enumerated() {
            XCTAssertEqual(int(bytes, cursor), 0x0201_4B50)
            let nameLength = short(bytes, cursor + 28)
            let extraLength = short(bytes, cursor + 30)
            let commentLength = short(bytes, cursor + 32)

            XCTAssertEqual(
                String(data: bytes.subdata(in: (cursor + 46)..<(cursor + 46 + nameLength)), encoding: .utf8),
                name
            )
            XCTAssertEqual(int(bytes, cursor + 24), sizes[index], "the size it really was")

            let local = int(bytes, cursor + 42)
            XCTAssertEqual(int(bytes, local), 0x0403_4B50)
            let localNameLength = short(bytes, local + 26)
            XCTAssertEqual(
                String(data: bytes.subdata(in: (local + 30)..<(local + 30 + localNameLength)), encoding: .utf8),
                name
            )

            cursor += 46 + nameLength + extraLength + commentLength
        }
        XCTAssertEqual(cursor, eocd, "the directory ends exactly where the end record begins")
    }

    // MARK: - The properties that make it worth encrypting

    /// Two identical files must not produce two identical payloads, or the
    /// archive says which entries are the same as each other.
    func testEveryEntryGetsItsOwnSalt() throws {
        let same = Data(repeating: 9, count: 64)
        let bytes = try EncryptedZip.archive(
            [
                EncryptedZip.Entry(name: "a.bin", bytes: same),
                EncryptedZip.Entry(name: "b.bin", bytes: same),
            ],
            password: password,
            modifiedAt: at
        )

        let payloadSize = 16 + 2 + same.count + 10
        let first = localHeaderBytes + "a.bin".count + aesExtraBytes
        let second = first + payloadSize + localHeaderBytes + "b.bin".count + aesExtraBytes

        let firstSalt = bytes.subdata(in: first..<(first + 16))
        let secondSalt = bytes.subdata(in: second..<(second + 16))
        XCTAssertNotEqual(firstSalt, secondSalt)

        let firstPayload = bytes.subdata(in: first..<(first + payloadSize))
        let secondPayload = bytes.subdata(in: second..<(second + payloadSize))
        XCTAssertNotEqual(firstPayload, secondPayload, "the same bytes must not encrypt the same way")
    }

    func testADifferentPasswordIsADifferentArchive() throws {
        let entry = [EncryptedZip.Entry(name: "r.bin", bytes: Data(repeating: 7, count: 32))]

        XCTAssertNotEqual(
            try archive(entry),
            try archive(entry, secret: "something else entirely")
        )
    }

    func testAnArchiveNeedsAPasswordAndSomethingToPutInIt() {
        XCTAssertThrowsError(try archive([]))
        XCTAssertThrowsError(
            try archive([EncryptedZip.Entry(name: "r.bin", bytes: Data([1]))], secret: "")
        )
    }

    /// A name that needs UTF-8 says so; one that does not, does not — an old
    /// reader is happier without a flag it has never heard of.
    func testANameWithAnAccentIsFlaggedAsUtf8() throws {
        let plain = Data([1, 2, 3])

        let ascii = try archive([EncryptedZip.Entry(name: "report.pdf", bytes: plain)])
        XCTAssertEqual(short(ascii, 6), 0x0001)

        let accented = try archive([EncryptedZip.Entry(name: "rapport-bébé.pdf", bytes: plain)])
        XCTAssertEqual(short(accented, 6), 0x0801)
    }

    func testTheTimestampIsTheOneItWasGiven() throws {
        let bytes = try archive([EncryptedZip.Entry(name: "r.bin", bytes: Data([1]))])

        // 14:30:08 packs as hour<<11 | minute<<5 | second/2.
        let expectedTime: Int = (14 << 11) | (30 << 5) | 4
        XCTAssertEqual(short(bytes, 10), expectedTime)
        // 2026-09-08 packs as (year-1980)<<9 | month<<5 | day.
        let expectedDate: Int = (46 << 9) | (9 << 5) | 8
        XCTAssertEqual(short(bytes, 12), expectedDate)
    }
}
