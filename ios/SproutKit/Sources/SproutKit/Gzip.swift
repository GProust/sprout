import Compression
import Foundation

/// Raised when bytes that claim to be gzip are not, or do not survive the trip.
public struct GzipError: Error, CustomStringConvertible {
    public let description: String
    init(_ description: String) { self.description = description }
}

/// gzip, framed by hand around the system's raw DEFLATE.
///
/// Android compresses a replica with `GZIPOutputStream` before sealing it
/// (`spec/wire-format.md` §2), so this side has to read and write the same
/// container. Apple ships DEFLATE in the Compression framework but nothing that
/// speaks gzip, and the ten bytes of header and eight of trailer around it are
/// small enough to write than to take a dependency for — the same trade
/// ADR-0013 made for the PDF and the workbook.
enum Gzip {

    private static let magic: [UInt8] = [0x1f, 0x8b]
    private static let deflateMethod: UInt8 = 8

    // FLG bits. Only FHCRC, FEXTRA, FNAME and FCOMMENT change the header's
    // length; FTEXT does not.
    private static let fhcrc: UInt8 = 1 << 1
    private static let fextra: UInt8 = 1 << 2
    private static let fname: UInt8 = 1 << 3
    private static let fcomment: UInt8 = 1 << 4
    private static let reservedFlags: UInt8 = 0xE0

    static func compress(_ data: Data) throws -> Data {
        var out = Data()
        out.append(contentsOf: magic)
        out.append(deflateMethod)
        out.append(0)                          // FLG: nothing optional present
        out.append(contentsOf: [0, 0, 0, 0])   // MTIME 0 — the clock must not
                                               // reach the bytes, or the same
                                               // input would seal differently
                                               // on every run.
        out.append(0)                          // XFL
        out.append(0xFF)                       // OS: unknown. Says nothing about
                                               // the phone that wrote it.
        out.append(try raw(data, operation: COMPRESSION_STREAM_ENCODE, hint: max(64, data.count)))
        out.append(littleEndian: Crc32.of(data))
        out.append(littleEndian: UInt32(truncatingIfNeeded: data.count))
        return out
    }

    static func decompress(_ data: Data, limit: Int) throws -> Data {
        guard data.count >= 18 else { throw GzipError("too short to be gzip") }
        let bytes = [UInt8](data)
        guard bytes[0] == magic[0], bytes[1] == magic[1] else { throw GzipError("not gzip") }
        guard bytes[2] == deflateMethod else { throw GzipError("gzip method \(bytes[2]) is not deflate") }

        let flags = bytes[3]
        guard flags & reservedFlags == 0 else { throw GzipError("gzip reserved flags are set") }

        // Skip whatever optional fields the writer chose to include. Java and
        // Node both write none, but a replica can arrive from anywhere and a
        // reader that assumes a fixed ten-byte header is one FNAME away from
        // decoding garbage.
        var cursor = 10
        func need(_ count: Int) throws {
            guard cursor + count <= bytes.count else { throw GzipError("gzip header runs past the end") }
        }
        if flags & fextra != 0 {
            try need(2)
            let length = Int(bytes[cursor]) | Int(bytes[cursor + 1]) << 8
            cursor += 2
            try need(length)
            cursor += length
        }
        for flag in [fname, fcomment] where flags & flag != 0 {
            while true {
                try need(1)
                let byte = bytes[cursor]
                cursor += 1
                if byte == 0 { break }
            }
        }
        if flags & fhcrc != 0 {
            try need(2)
            cursor += 2
        }

        // The trailer states the uncompressed size, which doubles as the
        // destination buffer's size and as a refusal: a file claiming more than
        // the ceiling is rejected before anything is allocated for it.
        let trailer = bytes.count - 8
        guard cursor <= trailer else { throw GzipError("gzip header overlaps its trailer") }
        let declaredCrc = UInt32(littleEndian: bytes, at: trailer)
        let declaredSize = UInt32(littleEndian: bytes, at: trailer + 4)
        guard declaredSize <= UInt32(limit) else {
            throw GzipError("gzip claims \(declaredSize) bytes, above the \(limit) byte ceiling")
        }

        let deflated = Data(bytes[cursor..<trailer])
        let inflated = try raw(deflated, operation: COMPRESSION_STREAM_DECODE, hint: Int(declaredSize))

        guard inflated.count == Int(declaredSize) else {
            throw GzipError("gzip declared \(declaredSize) bytes and produced \(inflated.count)")
        }
        guard Crc32.of(inflated) == declaredCrc else { throw GzipError("gzip checksum does not match") }
        return inflated
    }

    /// Runs the Compression framework's raw DEFLATE in either direction.
    ///
    /// Streamed rather than one-shot: `compression_encode_buffer` needs a
    /// destination big enough for the result, and for decoding that is exactly
    /// the number an untrusted file gets to claim. Feeding it through the
    /// stream API means the output grows as it is produced instead.
    static func raw(
        _ data: Data,
        operation: compression_stream_operation,
        hint: Int
    ) throws -> Data {
        guard !data.isEmpty || operation == COMPRESSION_STREAM_ENCODE else {
            throw GzipError("nothing to inflate")
        }

        let streamPointer = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { streamPointer.deallocate() }
        guard compression_stream_init(streamPointer, operation, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            throw GzipError("could not start the compression stream")
        }
        defer { compression_stream_destroy(streamPointer) }

        let chunk = max(4096, min(hint, 1 << 20))
        let destination = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
        defer { destination.deallocate() }

        var out = Data()
        let flags = Int32(COMPRESSION_STREAM_FINALIZE.rawValue)

        return try data.withUnsafeBytes { (source: UnsafeRawBufferPointer) -> Data in
            streamPointer.pointee.src_ptr = source.bindMemory(to: UInt8.self).baseAddress
                ?? UnsafePointer<UInt8>(bitPattern: 1)!
            streamPointer.pointee.src_size = data.count

            repeat {
                streamPointer.pointee.dst_ptr = destination
                streamPointer.pointee.dst_size = chunk

                switch compression_stream_process(streamPointer, flags) {
                case COMPRESSION_STATUS_OK, COMPRESSION_STATUS_END:
                    let produced = chunk - streamPointer.pointee.dst_size
                    if produced > 0 { out.append(destination, count: produced) }
                    if streamPointer.pointee.dst_size != 0 { return out }
                default:
                    throw GzipError(
                        operation == COMPRESSION_STREAM_ENCODE
                            ? "could not compress" : "damaged compressed data"
                    )
                }
            } while true
        }
    }
}

/// CRC-32 as gzip specifies it, table-free.
///
/// Twenty lines against a dependency, and it runs once per replica on a few
/// hundred kilobytes — the table would cost more to justify than to skip.
enum Crc32 {
    static func of(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 {
                crc = (crc >> 1) ^ (0xEDB8_8320 & (0 &- (crc & 1)))
            }
        }
        return crc ^ 0xFFFF_FFFF
    }
}

private extension Data {
    mutating func append(littleEndian value: UInt32) {
        append(contentsOf: [
            UInt8(truncatingIfNeeded: value),
            UInt8(truncatingIfNeeded: value >> 8),
            UInt8(truncatingIfNeeded: value >> 16),
            UInt8(truncatingIfNeeded: value >> 24),
        ])
    }
}

private extension UInt32 {
    init(littleEndian bytes: [UInt8], at index: Int) {
        self = UInt32(bytes[index])
            | UInt32(bytes[index + 1]) << 8
            | UInt32(bytes[index + 2]) << 16
            | UInt32(bytes[index + 3]) << 24
    }
}
