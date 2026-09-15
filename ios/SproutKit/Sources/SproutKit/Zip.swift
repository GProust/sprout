import Compression
import Foundation

/// A zip *writer*, and deliberately not a reader.
///
/// Two formats Sprout produces are zip containers: the `.xlsx` workbook, which
/// is a handful of XML parts in one, and the password-protected archive a parent
/// can put the export inside. Android gets both from `java.util.zip`; Foundation
/// has no equivalent, so the container is written here.
///
/// **A writer only, with no parser in it** — the same line ADR-0013 draws for
/// the workbook, and for the same reason: this is an app whose whole claim is
/// that it does not open files it did not write, and a reader is a parser.
///
/// Entries are deflated through the Compression framework, which is what
/// ``Gzip`` already uses, so no dependency is added.
public enum Zip {

    /// One file in the archive.
    public struct Entry {
        public let name: String
        public let data: Data
        /// Stored rather than deflated. The encrypted archive needs this: its
        /// entries are already ciphertext, and compressing ciphertext is wasted
        /// work that also leaks nothing useful.
        public let stored: Bool

        public init(name: String, data: Data, stored: Bool = false) {
            self.name = name
            self.data = data
            self.stored = stored
        }
    }

    /// The bytes of a zip file containing `entries`, in order.
    public static func archive(_ entries: [Entry]) throws -> Data {
        var out = Data()
        var directory = Data()
        var offsets: [Int] = []

        for entry in entries {
            offsets.append(out.count)
            let name = Data(entry.name.utf8)
            let crc = Crc32.of(entry.data)
            let payload = entry.stored
                ? entry.data
                : try Gzip.raw(entry.data, operation: COMPRESSION_STREAM_ENCODE, hint: entry.data.count)
            // Deflating can grow already-dense bytes. Storing them is smaller
            // and just as valid, so the smaller of the two wins.
            let deflated = !entry.stored && payload.count < entry.data.count
            let body = deflated ? payload : entry.data
            let method: UInt16 = deflated ? 8 : 0

            out.append(localHeader(name: name, method: method, crc: crc,
                                   compressed: body.count, uncompressed: entry.data.count))
            out.append(body)

            directory.append(
                centralHeader(
                    name: name,
                    method: method,
                    crc: crc,
                    compressed: body.count,
                    uncompressed: entry.data.count,
                    offset: offsets[offsets.count - 1]
                )
            )
        }

        let directoryOffset = out.count
        out.append(directory)
        out.append(endOfDirectory(count: entries.count, size: directory.count, offset: directoryOffset))
        return out
    }

    // MARK: - The three record types

    private static func localHeader(
        name: Data, method: UInt16, crc: UInt32, compressed: Int, uncompressed: Int
    ) -> Data {
        var header = Data()
        header.append(le: UInt32(0x0403_4B50))  // "PK\3\4"
        header.append(le: UInt16(20))           // version needed: 2.0, deflate
        header.append(le: UInt16(0x0800))       // UTF-8 names
        header.append(le: method)
        header.append(le: dosTime)
        header.append(le: dosDate)
        header.append(le: crc)
        header.append(le: UInt32(compressed))
        header.append(le: UInt32(uncompressed))
        header.append(le: UInt16(name.count))
        header.append(le: UInt16(0))            // no extra field
        header.append(name)
        return header
    }

    private static func centralHeader(
        name: Data, method: UInt16, crc: UInt32, compressed: Int, uncompressed: Int, offset: Int
    ) -> Data {
        var header = Data()
        header.append(le: UInt32(0x0201_4B50))  // "PK\1\2"
        header.append(le: UInt16(20))           // version made by
        header.append(le: UInt16(20))           // version needed
        header.append(le: UInt16(0x0800))
        header.append(le: method)
        header.append(le: dosTime)
        header.append(le: dosDate)
        header.append(le: crc)
        header.append(le: UInt32(compressed))
        header.append(le: UInt32(uncompressed))
        header.append(le: UInt16(name.count))
        header.append(le: UInt16(0))            // extra
        header.append(le: UInt16(0))            // comment
        header.append(le: UInt16(0))            // disk number
        header.append(le: UInt16(0))            // internal attributes
        header.append(le: UInt32(0))            // external attributes
        header.append(le: UInt32(offset))
        header.append(name)
        return header
    }

    private static func endOfDirectory(count: Int, size: Int, offset: Int) -> Data {
        var record = Data()
        record.append(le: UInt32(0x0605_4B50))  // "PK\5\6"
        record.append(le: UInt16(0))            // this disk
        record.append(le: UInt16(0))            // disk with the directory
        record.append(le: UInt16(count))
        record.append(le: UInt16(count))
        record.append(le: UInt32(size))
        record.append(le: UInt32(offset))
        record.append(le: UInt16(0))            // no comment
        return record
    }

    /// A fixed timestamp, and that is the point.
    ///
    /// Zip stores MS-DOS date and time, and writing the real clock would make
    /// two exports of the same range differ byte for byte — which is exactly what
    /// the workbook test compares. It is also one fewer thing the file says about
    /// when a parent was awake.
    private static let dosTime: UInt16 = 0      // 00:00:00
    private static let dosDate: UInt16 = 0x21   // 1 January 1980, the epoch of the format
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
