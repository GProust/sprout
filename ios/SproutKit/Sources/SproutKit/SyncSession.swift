import Foundation

/// Raised when the other end says something this app cannot make sense of.
public enum SyncSessionError: Error, Equatable {
    case hungUpBeforeSayingAnything
    case notSprout
    case otherVersion(UInt8)
    case implausibleSize(Int32)
    case droppedPartWayThrough
}

/// What two phones say to each other once they are connected
/// (`spec/wire-format.md` §4).
///
/// ```
/// "SPRTS" | version:1 | length:int32 big-endian | payload
/// ```
///
/// Deliberately ignorant of Bluetooth: it frames bytes, which is why the whole
/// exchange can be tested without a radio in sight. What travels is a replica
/// already sealed with AES-256-GCM under the household secret, so this layer has
/// no security of its own to get wrong.
public enum SyncSession {

    static let magic = Data("SPRTS".utf8)
    static let version: UInt8 = 1

    /// A replica of a year's tracking is a few hundred kilobytes. Eight
    /// megabytes leaves room for a family that logs far more than average, and
    /// still refuses to allocate a buffer because the other end claimed a
    /// ridiculous size.
    public static let maxPayloadBytes = 8 * 1024 * 1024

    public static func encodeFrame(_ payload: Data) -> Data {
        var frame = magic
        frame.append(version)
        // DataOutputStream.writeInt is big-endian and signed. Both halves of
        // that matter: the length is compared against zero on the way in.
        let length = Int32(payload.count).bigEndian
        withUnsafeBytes(of: length) { frame.append(contentsOf: $0) }
        frame.append(payload)
        return frame
    }

    /// Reads one frame from `data`, which must hold it whole.
    ///
    /// Used for the file path and by the tests; the radio path reads through
    /// ``readFrame(reading:)`` instead, because a stream arrives in pieces.
    public static func decodeFrame(_ data: Data) throws -> Data {
        var offset = 0
        return try readFrame { count in
            let end = min(offset + count, data.count)
            defer { offset = end }
            return Data(data[data.startIndex + offset ..< data.startIndex + end])
        }
    }

    /// Reads one frame by pulling from `reading`, which returns up to the number
    /// of bytes asked for and an empty `Data` at end of input.
    ///
    /// Taking a closure rather than an `InputStream` is what keeps this testable
    /// over an array of bytes, and lets the CoreBluetooth layer feed it from an
    /// L2CAP channel without this file knowing that is what happened.
    public static func readFrame(reading: (Int) throws -> Data) throws -> Data {
        func exactly(_ count: Int, orElse error: SyncSessionError) throws -> Data {
            var buffer = Data()
            while buffer.count < count {
                let chunk = try reading(count - buffer.count)
                if chunk.isEmpty { throw error }
                buffer.append(chunk)
            }
            return buffer
        }

        let magicBytes = try exactly(magic.count, orElse: .hungUpBeforeSayingAnything)
        guard magicBytes == magic else { throw SyncSessionError.notSprout }

        let versionByte = try exactly(1, orElse: .hungUpBeforeSayingAnything)[0]
        guard versionByte == version else { throw SyncSessionError.otherVersion(versionByte) }

        let lengthBytes = [UInt8](try exactly(4, orElse: .droppedPartWayThrough))
        let size = Int32(bitPattern:
            UInt32(lengthBytes[0]) << 24 | UInt32(lengthBytes[1]) << 16
                | UInt32(lengthBytes[2]) << 8 | UInt32(lengthBytes[3]))
        guard size >= 0, size <= Int32(maxPayloadBytes) else {
            throw SyncSessionError.implausibleSize(size)
        }

        return try exactly(Int(size), orElse: .droppedPartWayThrough)
    }
}
