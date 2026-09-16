import Foundation
import SproutData

/// The medicines a parent has put away from the dashboard, until the next dose
/// (BDR-16). The mirror of `ui/home/MedicineDismissals.kt`.
///
/// **A dismissal names the dose it was made against**, not just the medicine, so
/// it expires by itself: give another dose and the medicine's last dose is no
/// longer the one that was dismissed, and the line comes back. Nothing has to
/// clear it, which means nothing can forget to.
///
/// Device-local, in the ordinary settings rather than the keychain store:
/// "I have seen this" is a fact about a person looking at a screen, not about
/// this handset's identity (ADR-0018), and it is not synced — the other phone's
/// parent has not seen anything and should still be told.
struct MedicineDismissals {
    private static let key = "medicine_dismissed"

    let store: any DeviceLocalStore

    /// What is currently put away, as medicine uid to the dose it was dismissed
    /// against.
    ///
    /// Unreadable stored data reads as nothing put away, which is the safe way
    /// round: the failure shows a parent a line they had dismissed, rather than
    /// hiding one they had not.
    func all() -> [String: Int64] {
        guard let data = store.data(forKey: Self.key),
              let decoded = try? JSONDecoder().decode([String: Int64].self, from: data)
        else { return [:] }
        return decoded
    }

    /// Puts `uid` away until it has been given at something other than `doseAt`.
    func dismiss(uid: String, at doseAt: Int64) -> [String: Int64] {
        var updated = all()
        updated[uid] = doseAt
        store.set(try? JSONEncoder().encode(updated), forKey: Self.key)
        return updated
    }

    /// Forgets everything — the screenshot run starts from the same state each time.
    func clear() {
        store.set(nil, forKey: Self.key)
    }
}
