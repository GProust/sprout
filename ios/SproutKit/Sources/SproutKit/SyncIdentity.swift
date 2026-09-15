import Foundation

/// A fresh identifier for a row, unique across devices and never reused
/// (ADR-0007).
///
/// The local `id` stays the primary key, but it is a per-device counter:
/// feeding #7 here and feeding #7 on the partner's phone are unrelated. The uid
/// is what says "the same entry" when two replicas meet.
///
/// **Lowercase, and that is not cosmetic.** Android generates these with
/// `UUID.randomUUID().toString()`, which is lowercase, and the merge matches
/// rows by exact string comparison — `findByUid`, and a uid-to-id map. Swift's
/// `UUID().uuidString` is uppercase, so returning it unchanged would produce
/// rows that no Android phone ever recognises as the same entry: every merge
/// would duplicate instead of update, silently, forever.
public func newUid() -> String {
    UUID().uuidString.lowercased()
}

/// How long a tombstone is kept before compaction erases it for good.
///
/// Long enough that a partner who has not synced in a season still learns about
/// the deletion rather than resurrecting the row; short enough that deleted data
/// does not linger on the device forever.
public let tombstoneRetentionDays: Int64 = 180

/// ``tombstoneRetentionDays`` in milliseconds.
public let tombstoneRetentionMs: Int64 = tombstoneRetentionDays * 24 * 60 * 60 * 1000
