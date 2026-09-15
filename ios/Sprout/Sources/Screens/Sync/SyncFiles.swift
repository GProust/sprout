import Foundation
import SproutKit
import UniformTypeIdentifiers

/// Getting a replica — or an invitation — from one phone to the other, without a
/// server, a cloud folder, or a permission (ADR-0007, ADR-0008).
/// From `data/sync/SyncFiles.kt`.
///
/// Sprout writes the file and hands it to the share sheet; the parent picks the
/// channel they already trust. Nothing is uploaded by the app itself — there is
/// no code here that could — and the file never leaves the app's own temporary
/// directory until the parent chooses where it goes.
///
/// Invitations and replicas share one extension on purpose. The receiving phone
/// can tell them apart by looking (a replica starts with a magic header, an
/// invitation is JSON), so the parents only ever deal with "a Sprout file"
/// instead of having to know which of two kinds they were sent.
enum SyncFiles {

    static let fileExtension = "sprout"

    /// A type of our own rather than `public.data`, so that opening one of these
    /// files offers Sprout instead of every app that handles bytes. Declared as
    /// an exported type in `project.yml`; this is the same identifier Android's
    /// `application/vnd.sprout.sync` names.
    ///
    /// Looked up rather than declared with `UTType(exportedAs:)`, which traps
    /// when the identifier is missing from `Info.plist`. The declaration is what
    /// registers the type; this only needs a reference to it, and a plist that
    /// has drifted should degrade to "any file" rather than kill the app in the
    /// parent's hand.
    static let contentType = UTType("com.gproust.sprout.sync") ?? .data

    private static let directoryName = "sync"

    /// Writes `bytes` where the share sheet can reach them and returns the URL to
    /// hand out.
    ///
    /// The temporary directory is deliberate: the file is a courier, not a copy
    /// to keep. Each write replaces the previous one, and iOS reclaims the
    /// directory when it needs the space.
    static func stage(_ bytes: Data, named name: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(directoryName, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let url = directory
            .appendingPathComponent(name)
            .appendingPathExtension(fileExtension)
        try bytes.write(to: url, options: .atomic)
        return url
    }

    /// Reads a file the parent picked, or that another app sent us.
    ///
    /// **Capped rather than read whole.** The document type that brings a file
    /// here is one any app can produce, so the size behind a URL is theirs to
    /// choose — and reading an arbitrary file into memory is an out-of-memory
    /// kill dressed up as a tap on a file. The ceiling is
    /// ``SyncLimits/maxFileBytes``; the caller turns anything thrown here into
    /// "this file is not one Sprout can open", which is what it is.
    ///
    /// Read through a `FileHandle` rather than `Data(contentsOf:)` for two
    /// reasons: the cap can be applied *before* the bytes are in memory rather
    /// than after, and `Data(contentsOf:)` takes a URL of any scheme, which is
    /// the one shape in this app that could reach the network — `check_no_network.py`
    /// refuses it for that reason (ADR-0014).
    static func read(_ url: URL) throws -> Data {
        // A URL from the document picker is security-scoped: it is only readable
        // between these two calls, and only if the first one succeeded.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        // One byte past the ceiling, so "exactly at the limit" and "over it" are
        // different answers rather than the same truncated read.
        let limit = Int(SyncLimits.maxFileBytes)
        let bytes = try handle.read(upToCount: limit + 1) ?? Data()
        guard bytes.count <= limit else { throw SyncFileError.tooLarge }
        return bytes
    }
}

enum SyncFileError: Error {
    case tooLarge
}
