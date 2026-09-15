import CryptoKit
import Foundation

/// The secret every phone in a household holds, and the only thing that can
/// open a replica (ADR-0008).
///
/// It is the AES key directly — there is no derivation step between this and
/// the cipher, so its 32 bytes are worth exactly what the data is worth.
public struct SyncSecret: Equatable, Sendable {

    public static let sizeBytes = 32

    public let bytes: Data

    public init(bytes: Data) throws {
        guard bytes.count == Self.sizeBytes else {
            throw SyncSecretError.wrongSize(bytes.count)
        }
        self.bytes = bytes
    }

    public static func random() -> SyncSecret {
        // SymmetricKey draws from the platform CSPRNG. Going through CryptoKit
        // rather than SecRandomCopyBytes keeps Security out of this file for
        // the sake of one call.
        let key = SymmetricKey(size: .bits256)
        let bytes = key.withUnsafeBytes { Data($0) }
        // Force-try: 256 bits is 32 bytes and cannot be the wrong size.
        return try! SyncSecret(bytes: bytes)
    }

    /// Six characters both phones show after pairing, for the parents to read
    /// to each other — the moment that turns "a file arrived" into "we are
    /// paired with *each other*".
    ///
    /// Derived through SHA-256, so seeing the code tells an onlooker nothing
    /// about the secret. The alphabet leaves out `0`/`O` and `1`/`I`: this gets
    /// read aloud at 3 a.m.
    public func verificationCode() -> String {
        let digest = Array(SHA256.hash(data: bytes))
        let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        return String((0..<6).map { alphabet[Int(digest[$0]) % alphabet.count] })
    }
}

public enum SyncSecretError: Error, Equatable {
    case wrongSize(Int)
}
