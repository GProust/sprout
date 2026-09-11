import XCTest
@testable import SproutKit

final class SyncSecretTests: XCTestCase {

    func testVerificationCodeMatchesTheVector() throws {
        let vector = try Vectors.load("secret.json")
        let secret = try SyncSecret(bytes: Data(hex: XCTUnwrap(vector["secretHex"] as? String)))

        XCTAssertEqual(secret.verificationCode(), vector["verificationCode"] as? String)
    }

    /// The digest bytes are unsigned. Kotlin needs `and 0xFF` to say so and
    /// Swift's `UInt8` already does — but a secret whose digest starts with a
    /// high byte is where the two would part company, so pin one.
    func testHandlesADigestWithHighBytes() throws {
        let secret = try SyncSecret(bytes: Data(repeating: 0xFF, count: SyncSecret.sizeBytes))
        let code = secret.verificationCode()

        XCTAssertEqual(code.count, 6)
        XCTAssertTrue(code.allSatisfy { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".contains($0) })
    }

    func testTheAlphabetLeavesOutTheAmbiguousCharacters() throws {
        // Read aloud at 3 a.m., so no 0/O and no 1/I anywhere in the space.
        let codes = (0..<200).map { seed in
            try! SyncSecret(bytes: Data(repeating: UInt8(seed % 256), count: SyncSecret.sizeBytes))
                .verificationCode()
        }

        XCTAssertFalse(codes.joined().contains { "01OI".contains($0) })
    }

    func testRefusesASecretOfTheWrongSize() {
        for size in [0, 16, 31, 33, 64] {
            XCTAssertThrowsError(try SyncSecret(bytes: Data(repeating: 0, count: size))) { error in
                XCTAssertEqual(error as? SyncSecretError, .wrongSize(size))
            }
        }
    }

    func testRandomSecretsDiffer() {
        let secrets = Set((0..<50).map { _ in SyncSecret.random().bytes })
        XCTAssertEqual(secrets.count, 50)
    }
}

final class GzipTests: XCTestCase {

    func testRoundTrip() throws {
        for payload: Data in [
            Data(),
            Data("x".utf8),
            Data(String(repeating: "the same line over and over\n", count: 2_000).utf8),
            Data((0..<70_000).map { UInt8($0 % 256) }),          // barely compressible
        ] {
            let there = try Gzip.compress(payload)
            let back = try Gzip.decompress(there, limit: SyncLimits.maxFileBytes)
            XCTAssertEqual(back, payload, "round trip for \(payload.count) bytes")
        }
    }

    func testProducesSomethingThatLooksLikeGzip() throws {
        let compressed = try Gzip.compress(Data("hello".utf8))

        XCTAssertEqual([UInt8](compressed.prefix(3)), [0x1f, 0x8b, 0x08])
    }

    /// The clock must not reach the bytes, or the same replica would seal
    /// differently on every run and nothing would be reproducible.
    func testTheOutputDoesNotDependOnTheClock() throws {
        let payload = Data("deterministic".utf8)

        XCTAssertEqual(try Gzip.compress(payload), try Gzip.compress(payload))
    }

    func testReadsGzipWrittenElsewhere() throws {
        // The sealed vector's plaintext is gzipped by Node before encryption,
        // so opening it exercises exactly this path against a third writer.
        let vector = try Vectors.load("sealed.json")
        let sealed = try XCTUnwrap(Data(base64Encoded: XCTUnwrap(vector["sealedBase64"] as? String)))
        let secret = try SyncSecret(bytes: Data(hex: XCTUnwrap(Vectors.load("secret.json")["secretHex"] as? String)))

        XCTAssertEqual(
            String(data: try SyncCrypto.open(sealed, secret: secret), encoding: .utf8),
            vector["plaintextUtf8"] as? String
        )
    }

    func testRefusesThingsThatAreNotGzip() {
        for bytes: Data in [
            Data(),
            Data("not gzip".utf8),
            Data(repeating: 0, count: 32),
            Data([0x1f, 0x8b, 0x09]) + Data(repeating: 0, count: 32),   // wrong method
        ] {
            XCTAssertThrowsError(try Gzip.decompress(bytes, limit: SyncLimits.maxFileBytes))
        }
    }

    /// The trailer declares the uncompressed size, and a file claiming more
    /// than the ceiling is refused before anything is allocated for it.
    func testRefusesAFileClaimingMoreThanTheCeiling() throws {
        var compressed = [UInt8](try Gzip.compress(Data("small".utf8)))
        // Rewrite ISIZE to 64 MiB.
        let isize = compressed.count - 4
        compressed[isize] = 0x00
        compressed[isize + 1] = 0x00
        compressed[isize + 2] = 0x00
        compressed[isize + 3] = 0x04

        XCTAssertThrowsError(try Gzip.decompress(Data(compressed), limit: SyncLimits.maxFileBytes))
    }

    func testRefusesACorruptedBody() throws {
        var compressed = [UInt8](try Gzip.compress(Data(String(repeating: "abc", count: 500).utf8)))
        compressed[15] ^= 0xFF

        XCTAssertThrowsError(try Gzip.decompress(Data(compressed), limit: SyncLimits.maxFileBytes))
    }
}

final class SyncIdentityTests: XCTestCase {

    /// Android generates these with `UUID.randomUUID().toString()`, which is
    /// lowercase, and the merge matches rows by exact string. An uppercase uid
    /// is a row no Android phone recognises as the same entry — every merge
    /// would duplicate instead of update, silently.
    func testUidsAreLowercase() {
        for _ in 0..<50 {
            let uid = newUid()
            XCTAssertEqual(uid, uid.lowercased())
        }
    }

    func testUidsLookLikeAHyphenatedUuid() {
        let uid = newUid()

        XCTAssertEqual(uid.count, 36)
        XCTAssertEqual(uid.split(separator: "-").map(\.count), [8, 4, 4, 4, 12])
        XCTAssertNotNil(UUID(uuidString: uid))
    }

    func testUidsAreNotReused() {
        XCTAssertEqual(Set((0..<500).map { _ in newUid() }).count, 500)
    }

    /// Both apps compact tombstones on the same clock, or one phone erases a
    /// deletion the other is still expecting to hear about.
    func testTombstoneRetentionMatchesAndroid() {
        XCTAssertEqual(tombstoneRetentionDays, 180)
        XCTAssertEqual(tombstoneRetentionMs, 180 * 24 * 60 * 60 * 1000)
    }
}
