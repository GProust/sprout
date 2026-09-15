import CryptoKit
import Foundation

/// Raised when a replica cannot be opened with the secret this phone holds.
///
/// The cases exist to tell a parent what happened, not to tell an attacker: a
/// wrong key and altered bytes are the same event to AES-GCM, and both arrive
/// here as ``notOursOrDamaged``.
public enum SyncCryptoError: Error, Equatable {
    case tooShort
    case notAReplica
    case newerVersion(UInt8)
    case notOursOrDamaged
    case damaged
}

/// Wraps a payload for the journey between two phones (`spec/wire-format.md` §2).
///
/// ```
/// "SPRT" | version:1 | nonce:12 | AES-256-GCM(gzip(plaintext)) || tag:16
/// ```
///
/// The five-byte header is authenticated as associated data, so the version
/// cannot be rewritten to talk an older reader into a different interpretation.
public enum SyncCrypto {

    static let magic = Data("SPRT".utf8)
    static let version: UInt8 = 1
    static let nonceBytes = 12
    static let tagBytes = 16

    static var header: Data { magic + Data([version]) }

    /// Compresses, encrypts and frames `plaintext`.
    public static func seal(_ plaintext: Data, secret: SyncSecret) throws -> Data {
        let header = Self.header
        let nonce = AES.GCM.Nonce()
        let box = try AES.GCM.seal(
            Gzip.compress(plaintext),
            using: SymmetricKey(data: secret.bytes),
            nonce: nonce,
            authenticating: header
        )
        // Java hands back ciphertext and tag as one run of bytes, and that is
        // what goes on the wire; CryptoKit keeps them apart, so join them here
        // rather than let `combined` decide the layout for us.
        return header + Data(nonce) + box.ciphertext + box.tag
    }

    /// Unwraps what ``seal(_:secret:)`` produced.
    public static func open(_ sealed: Data, secret: SyncSecret) throws -> Data {
        let headerSize = magic.count + 1
        guard sealed.count >= headerSize + nonceBytes + tagBytes else {
            throw SyncCryptoError.tooShort
        }
        let bytes = [UInt8](sealed)
        let header = Data(bytes[0..<headerSize])
        guard header.prefix(magic.count) == magic else { throw SyncCryptoError.notAReplica }
        guard bytes[magic.count] == version else {
            throw SyncCryptoError.newerVersion(bytes[magic.count])
        }

        let bodyStart = headerSize + nonceBytes
        let tagStart = sealed.count - tagBytes
        let plain: Data
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: Data(bytes[headerSize..<bodyStart])),
                ciphertext: Data(bytes[bodyStart..<tagStart]),
                tag: Data(bytes[tagStart...])
            )
            plain = try AES.GCM.open(box, using: SymmetricKey(data: secret.bytes), authenticating: header)
        } catch {
            throw SyncCryptoError.notOursOrDamaged
        }

        do {
            return try Gzip.decompress(plain, limit: SyncLimits.maxFileBytes)
        } catch {
            throw SyncCryptoError.damaged
        }
    }
}
