import Foundation

/// What a file has to be under before Sprout will try to read it (ADR-0014).
///
/// Everything that parses here parses a file that *arrived* — an invitation or
/// a replica, handed over by whatever app the other parent used — so "a file
/// Sprout was asked to open" means "bytes chosen by whoever sent them", and the
/// first thing every decoder does is decide whether they are worth parsing at
/// all.
///
/// Both ceilings are plausibility checks, not security boundaries: what decides
/// whether a replica is yours is the AES-GCM tag in ``SyncCrypto``. These only
/// stop a file no Sprout ever wrote from being expensive to refuse.
public enum SyncLimits {

    /// The largest file Sprout will read into memory. A replica of a year's
    /// tracking is a few hundred kilobytes.
    public static let maxFileBytes = 8 * 1024 * 1024

    /// How deeply a Sprout document may nest. A replica is four levels deep at
    /// its worst — the document, a list, a row, a value.
    public static let maxJsonDepth = 32

    /// Whether `text` nests deeper than ``maxJsonDepth``.
    ///
    /// Counts brackets rather than parsing, which makes it a scan of the string
    /// and not a second implementation of JSON. Contents of a double-quoted
    /// string don't count — a parent whose note is a row of brackets is writing
    /// a note, not a document — and a backslash inside one escapes whatever
    /// follows it, so `"\""` stays open.
    ///
    /// Everything outside such a string counts. Over-counting refuses a file no
    /// Sprout ever produced; under-counting would hand the parser the very input
    /// this exists to keep away from it.
    ///
    /// Checked *before* parsing, not around it. `JSONSerialization` fails on
    /// deep nesting in its own way and at its own depth, and neither is
    /// something a `catch` here can be relied on to hold — depth is the one
    /// property of the input that can be known in advance.
    public static func exceedsMaxJsonDepth(_ text: String) -> Bool {
        var depth = 0
        var inString = false
        var escaped = false
        for character in text.unicodeScalars {
            if inString {
                if escaped { escaped = false }
                else if character == "\\" { escaped = true }
                else if character == "\"" { inString = false }
                continue
            }
            switch character {
            case "\"":
                inString = true
            case "{", "[":
                depth += 1
                if depth > maxJsonDepth { return true }
            case "}", "]":
                if depth > 0 { depth -= 1 }
            default:
                break
            }
        }
        return false
    }
}
