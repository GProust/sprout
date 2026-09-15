import XCTest
@testable import SproutKit

final class SyncInvitationTests: XCTestCase {

    private func vectorFields() throws -> [String: Any] {
        try XCTUnwrap(Vectors.load("invitation.json")["fields"] as? [String: Any])
    }

    func testReadsAnInvitationThisAppDidNotWrite() throws {
        let fields = try vectorFields()
        let createdAt = try XCTUnwrap((fields["createdAt"] as? NSNumber)?.int64Value)
        let bytes = try JSONSerialization.data(withJSONObject: fields)

        let invitation = try SyncInvitationCodec.decode(bytes, now: createdAt + 1000)

        XCTAssertEqual(invitation.householdId, fields["householdId"] as? String)
        XCTAssertEqual(invitation.fromName, fields["fromName"] as? String)
        XCTAssertEqual(invitation.createdAt, createdAt)
        XCTAssertEqual(invitation.secret.bytes.base64EncodedString(), fields["secret"] as? String)
    }

    /// Every expiry and version case the vector lists, so the boundary is
    /// checked as the format states it rather than as this file remembers it.
    func testHonoursEveryCaseInTheVector() throws {
        let fields = try vectorFields()
        let createdAt = try XCTUnwrap((fields["createdAt"] as? NSNumber)?.int64Value)

        for testCase in try Vectors.cases(in: "invitation.json") {
            let name = try XCTUnwrap(testCase["name"] as? String)
            let now = try XCTUnwrap((testCase["now"] as? NSNumber)?.int64Value)
            let expect = try XCTUnwrap(testCase["expect"] as? String)

            var document = fields
            if let override = testCase["formatVersion"] { document["formatVersion"] = override }
            let bytes = try JSONSerialization.data(withJSONObject: document)

            switch expect {
            case "accepted":
                XCTAssertNoThrow(try SyncInvitationCodec.decode(bytes, now: now), name)
            case "expired":
                XCTAssertThrowsError(try SyncInvitationCodec.decode(bytes, now: now), name) {
                    XCTAssertEqual($0 as? SyncInvitationError, .expired, name)
                }
            case "tooNew":
                XCTAssertThrowsError(try SyncInvitationCodec.decode(bytes, now: now), name) {
                    XCTAssertEqual($0 as? SyncInvitationError, .tooNew(formatVersion: 2), name)
                }
            default:
                XCTFail("unknown expectation \(expect) in case \(name)")
            }
            _ = createdAt
        }
    }

    func testRoundTrip() throws {
        let invitation = SyncInvitation(
            householdId: "household-1",
            secret: .random(),
            createdAt: 1_757_400_000_000,
            fromName: "Alex"
        )

        let decoded = try SyncInvitationCodec.decode(
            try SyncInvitationCodec.encode(invitation),
            now: invitation.createdAt + 60_000
        )

        XCTAssertEqual(decoded, invitation)
    }

    /// Android writes the name with `optString`, so an absent one is empty
    /// rather than a refusal — a nameless phone is not a bad file.
    func testAMissingNameIsNotAFailure() throws {
        var fields = try vectorFields()
        let createdAt = try XCTUnwrap((fields["createdAt"] as? NSNumber)?.int64Value)
        fields.removeValue(forKey: "fromName")

        let invitation = try SyncInvitationCodec.decode(
            try JSONSerialization.data(withJSONObject: fields),
            now: createdAt + 1000
        )

        XCTAssertEqual(invitation.fromName, "")
    }

    func testRefusesThingsThatAreNotInvitations() throws {
        let now: Int64 = 1_757_400_000_000

        for bytes in [
            Data("not json at all".utf8),
            Data("[]".utf8),
            Data("{}".utf8),                                  // no formatVersion
            Data(#"{"formatVersion":1}"#.utf8),               // nothing else
            Data(),
        ] {
            XCTAssertThrowsError(try SyncInvitationCodec.decode(bytes, now: now)) { error in
                XCTAssertEqual(error as? SyncInvitationError, .unreadable, String(decoding: bytes, as: UTF8.self))
            }
        }
    }

    /// A secret of the wrong length is not a secret, however well-formed the
    /// document around it is.
    func testRefusesAMisSizedSecret() throws {
        var fields = try vectorFields()
        let createdAt = try XCTUnwrap((fields["createdAt"] as? NSNumber)?.int64Value)
        fields["secret"] = Data(repeating: 1, count: 16).base64EncodedString()

        XCTAssertThrowsError(
            try SyncInvitationCodec.decode(
                try JSONSerialization.data(withJSONObject: fields), now: createdAt + 1000
            )
        ) { XCTAssertEqual($0 as? SyncInvitationError, .unreadable) }
    }

    /// Nesting deep enough to trouble a parser is refused by the depth scan
    /// before the parser ever sees it (ADR-0014).
    func testRefusesSomethingDeeplyNested() {
        let deep = Data((String(repeating: "[", count: 20_000) + String(repeating: "]", count: 20_000)).utf8)

        XCTAssertThrowsError(try SyncInvitationCodec.decode(deep, now: 0)) { error in
            XCTAssertEqual(error as? SyncInvitationError, .unreadable)
        }
    }
}
