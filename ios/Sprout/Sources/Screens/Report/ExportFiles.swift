import Foundation
import SproutData

/// Getting a report off the phone, by the same route a replica already takes
/// (ADR-0007): Sprout writes the file and hands it to the system share sheet, and
/// the parent picks the channel they already trust.
///
/// **Nothing is uploaded.** The app makes no network call and could not upload it
/// if it wanted to — `check_no_network.py` fails the build if that stops being
/// true. The file sits in a cache directory until the parent chooses where it
/// goes, and **each export replaces the one before it**: a report is a courier,
/// not an archive, and a health record left lying in the cache is a health record
/// left lying about.
enum ExportFiles {

    static let pdfMime = "application/pdf"

    private static let directoryName = "reports"

    /// Writes a file where the share sheet can reach it and returns its URL.
    ///
    /// The directory is emptied first: yesterday's report is of no use to anyone,
    /// and keeping it would leave a second copy of a baby's health record on the
    /// device for no reason.
    static func stage(_ data: Data, named fileName: String) throws -> URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let directory = caches.appendingPathComponent(directoryName, isDirectory: true)

        if FileManager.default.fileExists(atPath: directory.path) {
            for stale in (try? FileManager.default.contentsOfDirectory(
                at: directory, includingPropertiesForKeys: nil
            )) ?? [] {
                try? FileManager.default.removeItem(at: stale)
            }
        } else {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }

        let file = directory.appendingPathComponent(fileName)
        // `.completeFileProtection`: the staged copy is encrypted at rest under
        // the passcode like the database is, so a report waiting to be shared is
        // not the one unguarded copy of a baby's record on the phone.
        try data.write(to: file, options: [.atomic, .completeFileProtection])
        return file
    }

    /// A file name that says whose record it is and what it covers, so a doctor
    /// with three of them in a downloads folder can tell them apart.
    ///
    /// The baby's name is reduced to plain characters: it travels through mail
    /// clients and file managers of every vintage, and a name with a slash in it
    /// is a file that fails to save rather than a file with an odd name.
    static func fileName(
        babyName: String,
        from: CalendarDay,
        to: CalendarDay,
        extension fileExtension: String
    ) -> String {
        let safe = String(
            babyName
                .map { $0.isLetter || $0.isNumber ? $0 : "-" }
                .prefix(24)
        )
        .trimmingCharacters(in: CharacterSet(charactersIn: "-"))

        let name = safe.isEmpty ? "baby" : safe
        return "Sprout-\(name)-\(iso(from))_\(iso(to)).\(fileExtension)"
    }

    /// `2026-09-08`, and deliberately not the reader's date format: this is part
    /// of a file name, where it has to sort and has to survive being typed out.
    private static func iso(_ day: CalendarDay) -> String {
        String(format: "%04d-%02d-%02d", day.year, day.month, day.day)
    }
}
