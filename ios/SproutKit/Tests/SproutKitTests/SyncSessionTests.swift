import XCTest
@testable import SproutKit

final class SyncSessionTests: XCTestCase {

    func testFramesExactlyAsTheVectorSays() throws {
        let vector = try Vectors.load("session-frame.json")
        let payload = Data(try XCTUnwrap(vector["payloadUtf8"] as? String).utf8)
        let expected = try XCTUnwrap(Data(base64Encoded: XCTUnwrap(vector["frameBase64"] as? String)))

        XCTAssertEqual(SyncSession.encodeFrame(payload), expected)
    }

    func testReadsAFrameThisAppDidNotWrite() throws {
        let vector = try Vectors.load("session-frame.json")
        let frame = try XCTUnwrap(Data(base64Encoded: XCTUnwrap(vector["frameBase64"] as? String)))
        let expected = try XCTUnwrap(vector["payloadUtf8"] as? String)

        XCTAssertEqual(String(data: try SyncSession.decodeFrame(frame), encoding: .utf8), expected)
    }

    func testRoundTrip() throws {
        let payload = Data((0..<10_000).map { UInt8($0 % 251) })
        XCTAssertEqual(try SyncSession.decodeFrame(SyncSession.encodeFrame(payload)), payload)
    }

    func testEmptyPayloadIsAValidFrame() throws {
        XCTAssertEqual(try SyncSession.decodeFrame(SyncSession.encodeFrame(Data())), Data())
    }

    /// A stream arrives in pieces, and a reader that assumes one read returns
    /// everything works on a pipe and fails on a radio. Feed it one byte at a
    /// time, which is the worst case and the one least likely to be exercised
    /// by accident.
    func testReassemblesAFrameArrivingOneByteAtATime() throws {
        let payload = Data((0..<5_000).map { UInt8($0 % 251) })
        let frame = SyncSession.encodeFrame(payload)
        var offset = 0

        let read = try SyncSession.readFrame { _ in
            guard offset < frame.count else { return Data() }
            defer { offset += 1 }
            return Data([frame[frame.startIndex + offset]])
        }

        XCTAssertEqual(read, payload)
    }

    func testRefusesSomethingThatIsNotSprout() {
        let notUs = Data("HTTP/1.1 200 OK".utf8)

        XCTAssertThrowsError(try SyncSession.decodeFrame(notUs)) { error in
            XCTAssertEqual(error as? SyncSessionError, .notSprout)
        }
    }

    func testRefusesANewerVersion() {
        var frame = SyncSession.encodeFrame(Data("hello".utf8))
        frame[frame.startIndex + SyncSession.magic.count] = 2

        XCTAssertThrowsError(try SyncSession.decodeFrame(frame)) { error in
            XCTAssertEqual(error as? SyncSessionError, .otherVersion(2))
        }
    }

    /// The length is signed, and the check has to be on both sides of zero:
    /// a negative size would otherwise become a very large allocation.
    func testRefusesANegativeLength() {
        var frame = SyncSession.magic
        frame.append(SyncSession.version)
        frame.append(contentsOf: [0xFF, 0xFF, 0xFF, 0xFF]) // -1
        frame.append(Data(repeating: 0, count: 8))

        XCTAssertThrowsError(try SyncSession.decodeFrame(frame)) { error in
            XCTAssertEqual(error as? SyncSessionError, .implausibleSize(-1))
        }
    }

    func testRefusesAnImplausiblyLargeLength() {
        var frame = SyncSession.magic
        frame.append(SyncSession.version)
        frame.append(contentsOf: [0x7F, 0xFF, 0xFF, 0xFF]) // Int32.max
        frame.append(Data(repeating: 0, count: 8))

        XCTAssertThrowsError(try SyncSession.decodeFrame(frame)) { error in
            XCTAssertEqual(error as? SyncSessionError, .implausibleSize(Int32.max))
        }
    }

    func testRefusesAFrameThatStopsPartWayThrough() {
        let frame = SyncSession.encodeFrame(Data(repeating: 7, count: 1000))

        XCTAssertThrowsError(try SyncSession.decodeFrame(frame.prefix(100))) { error in
            XCTAssertEqual(error as? SyncSessionError, .droppedPartWayThrough)
        }
    }

    func testRefusesAnEndThatSaysNothingAtAll() {
        XCTAssertThrowsError(try SyncSession.decodeFrame(Data())) { error in
            XCTAssertEqual(error as? SyncSessionError, .hungUpBeforeSayingAnything)
        }
    }
}
