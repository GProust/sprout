import XCTest
@testable import SproutKit

/// Checks the seal against `spec/vectors/sealed.json` — bytes produced by
/// neither app, so passing means agreeing with the format rather than with
/// Android.
final class SyncCryptoTests: XCTestCase {

    private func secret() throws -> SyncSecret {
        let hex = try XCTUnwrap(Vectors.load("secret.json")["secretHex"] as? String)
        return try SyncSecret(bytes: Data(hex: hex))
    }

    func testOpensAReplicaThisAppDidNotWrite() throws {
        let vector = try Vectors.load("sealed.json")
        let sealed = try XCTUnwrap(Data(base64Encoded: XCTUnwrap(vector["sealedBase64"] as? String)))
        let expected = try XCTUnwrap(vector["plaintextUtf8"] as? String)

        let opened = try SyncCrypto.open(sealed, secret: secret())

        XCTAssertEqual(String(data: opened, encoding: .utf8), expected)
    }

    func testAlteredBytesFailTheTag() throws {
        let vector = try Vectors.load("sealed.json")
        let tampered = try XCTUnwrap(Data(base64Encoded: XCTUnwrap(vector["tamperedBase64"] as? String)))

        XCTAssertThrowsError(try SyncCrypto.open(tampered, secret: secret())) { error in
            XCTAssertEqual(error as? SyncCryptoError, .notOursOrDamaged)
        }
    }

    /// The header is associated data. Rewriting the version must fail the tag
    /// rather than persuade a reader to interpret the body differently — which
    /// is the whole reason it is authenticated.
    func testRewritingTheVersionFailsTheTag() throws {
        let vector = try Vectors.load("sealed.json")
        let rewritten = try XCTUnwrap(
            Data(base64Encoded: XCTUnwrap(vector["rewrittenVersionBase64"] as? String))
        )

        XCTAssertThrowsError(try SyncCrypto.open(rewritten, secret: secret())) { error in
            XCTAssertEqual(error as? SyncCryptoError, .newerVersion(2))
        }
    }

    func testAnotherHouseholdsSecretCannotOpenIt() throws {
        let vector = try Vectors.load("sealed.json")
        let sealed = try XCTUnwrap(Data(base64Encoded: XCTUnwrap(vector["sealedBase64"] as? String)))
        let stranger = try SyncSecret(bytes: Data(repeating: 0xAB, count: SyncSecret.sizeBytes))

        XCTAssertThrowsError(try SyncCrypto.open(sealed, secret: stranger)) { error in
            XCTAssertEqual(error as? SyncCryptoError, .notOursOrDamaged)
        }
    }

    func testRoundTrip() throws {
        let secret = try secret()
        // Long enough that gzip actually has something to do, and repetitive
        // enough that it compresses — a replica is mostly repeated field names.
        let plaintext = Data(String(repeating: #"{"uid":"abc","amountMl":90},"#, count: 500).utf8)

        let opened = try SyncCrypto.open(try SyncCrypto.seal(plaintext, secret: secret), secret: secret)

        XCTAssertEqual(opened, plaintext)
    }

    func testEmptyPayloadRoundTrips() throws {
        let secret = try secret()
        XCTAssertEqual(try SyncCrypto.open(try SyncCrypto.seal(Data(), secret: secret), secret: secret), Data())
    }

    /// Two seals of the same bytes must differ: the nonce is drawn fresh each
    /// time, and a fixed one would leak far more than it saves.
    func testEverySealUsesANewNonce() throws {
        let secret = try secret()
        let plaintext = Data("the same replica twice".utf8)

        let first = try SyncCrypto.seal(plaintext, secret: secret)
        let second = try SyncCrypto.seal(plaintext, secret: secret)

        XCTAssertNotEqual(first, second)
        XCTAssertEqual(try SyncCrypto.open(first, secret: secret), plaintext)
        XCTAssertEqual(try SyncCrypto.open(second, secret: secret), plaintext)
    }

    func testRefusesBytesThatAreNotAReplica() throws {
        let secret = try secret()

        XCTAssertThrowsError(try SyncCrypto.open(Data("nope".utf8), secret: secret)) { error in
            XCTAssertEqual(error as? SyncCryptoError, .tooShort)
        }
        // Long enough to hold a header, but not a Sprout one.
        let wrongMagic = Data("XXXX".utf8) + Data(repeating: 0, count: 40)
        XCTAssertThrowsError(try SyncCrypto.open(wrongMagic, secret: secret)) { error in
            XCTAssertEqual(error as? SyncCryptoError, .notAReplica)
        }
    }
}
