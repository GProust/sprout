import Foundation

/// Every "what's new" announcement this app carries, oldest first (BDR-22), from
/// `ANNOUNCEMENT_IDS` in `data/WhatsNew.kt`.
///
/// An announcement is a highlight, not a changelog line: one for a release that
/// brings something a parent would want to know is there, none for a release of
/// fixes. The ids are one sequence shared with Android, so the same id is the
/// same text on both phones. Append, never renumber or reuse one: a phone
/// remembers the newest id it has shown, and a reused number would read as
/// already seen.
public let announcementIds: [Int] = [1, 2]

/// Which announcements the dashboard's card offers, newest first.
///
/// `lastSeen` is the newest id this phone has already shown or put away, and
/// nil when it has never stored one. That happens in two cases, and only one of
/// them reaches the dashboard:
/// - A fresh install marks every announcement seen when onboarding finishes:
///   everything is new to someone who has just arrived, and nothing has
///   *changed*.
/// - A phone updating from a version before announcements existed has a
///   profile and nothing stored. It is offered the newest announcement only,
///   not the whole history.
public func announcementsToShow(available: [Int], lastSeen: Int?) -> [Int] {
    guard let newest = available.max() else { return [] }
    guard let lastSeen else { return [newest] }
    return available.filter { $0 > lastSeen }.sorted(by: >)
}

/// The newest announcement this phone has shown, from `WhatsNewSettings.kt`.
///
/// In the ordinary settings (``UserDefaultsStore``), not the Keychain: "I have
/// read this" is about a person, not about this handset, so a restored phone
/// carries it and does not replay what was already read (ADR-0011, ADR-0018).
/// It is not synced: the other parent has not read anything on this phone.
public struct WhatsNewSettings {
    private static let key = "whats_new_last_seen"

    private let store: any DeviceLocalStore

    public init(store: any DeviceLocalStore) {
        self.store = store
    }

    /// The newest id shown so far, or nil if none ever was.
    public var lastSeen: Int? {
        store.int64(forKey: Self.key).map { Int($0) }
    }

    /// Every announcement this version carries counts as seen: the card was
    /// read or put away, or onboarding has just finished and there is nothing
    /// to catch up on.
    public func markAllSeen() {
        guard let newest = announcementIds.max() else { return }
        store.setInt64(Int64(newest), forKey: Self.key)
    }
}
