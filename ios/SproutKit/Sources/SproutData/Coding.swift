import Foundation

// Two columns hold a list inside a TEXT value, because Room's converters put
// them there. The encodings are Android's, reproduced exactly: the database is
// local, but a phone restored from a backup and a replica read by the sync
// engine both expect these bytes, and "nearly the same" is a column that parses
// to nothing.

/// Reminder times, as Room stores them: minutes since midnight, comma-separated.
public enum IntListCoding {

    public static func encode(_ values: [Int]) -> String {
        values.map(String.init).joined(separator: ",")
    }

    /// Blank entries are dropped rather than read as zero — Kotlin's
    /// `filter { it.isNotBlank() }`. A trailing comma is a formatting artefact,
    /// not a reminder at midnight.
    public static func decode(_ text: String?) -> [Int] {
        guard let text else { return [] }
        return text
            .split(separator: ",", omittingEmptySubsequences: true)
            .compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
    }
}

/// Breastfeeding segments, as Room stores them: `SIDE,start,end` triples joined
/// by `;`.
public enum NursingSegmentCoding {

    public static func encode(_ segments: [NursingSegment]) -> String {
        segments
            .map { "\($0.side.rawValue),\($0.startTime),\($0.endTime)" }
            .joined(separator: ";")
    }

    /// A malformed triple is skipped, not fatal.
    ///
    /// Kotlin's destructuring would throw on a triple with the wrong number of
    /// parts, and this column can arrive from a merge with whatever the other
    /// phone wrote. Losing one segment of one feed is a worse outcome than
    /// nothing only if the alternative is losing the whole session, which
    /// throwing here would do.
    public static func decode(_ text: String?) -> [NursingSegment] {
        guard let text, !text.isEmpty else { return [] }
        return text.split(separator: ";", omittingEmptySubsequences: true).compactMap { triple in
            let parts = triple.split(separator: ",", omittingEmptySubsequences: false)
            guard parts.count == 3,
                  let side = BreastSide(rawValue: String(parts[0])),
                  let start = Int64(parts[1]),
                  let end = Int64(parts[2])
            else { return nil }
            return NursingSegment(side: side, startTime: start, endTime: end)
        }
    }
}
