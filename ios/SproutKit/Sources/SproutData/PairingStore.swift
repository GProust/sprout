import Foundation
import SproutKit

/// This device's own name in the household, minted once and kept for good.
///
/// It must not travel to a new phone, so it lives in the device-only store: a
/// restore that kept it would leave two handsets answering to one id, and
/// ``HouseholdDevices`` keys on that id — the list would show one entry where
/// there are two phones, and "remove this phone" would remove whichever of them
/// the entry happened to be describing (ADR-0011).
///
/// Android has a second, older place to look; this does not, because no iPhone
/// has ever run a Sprout that put it somewhere else.
public enum DeviceIdentity {

    static let key = "device_id"

    /// This device's id, creating and persisting one the first time it is asked
    /// for.
    public static func id(in store: any DeviceLocalStore) -> String {
        if let existing = store.string(forKey: key), !existing.isEmpty { return existing }
        let minted = newUid()
        store.setString(minted, forKey: key)
        return minted
    }
}

/// Who this phone is paired with, and on what terms (ADR-0008).
public struct Pairing: Equatable, Sendable {
    public var householdId: String
    public var secret: SyncSecret
    /// When the pairing was made — the cut-off for "share from the pairing
    /// forward".
    public var pairedAt: Int64
    /// The stash switch: whether *this* phone sends its expressed-milk log.
    public var shareStash: Bool

    public init(householdId: String, secret: SyncSecret, pairedAt: Int64, shareStash: Bool) {
        self.householdId = householdId
        self.secret = secret
        self.pairedAt = pairedAt
        self.shareStash = shareStash
    }

    /// The six characters both phones show, for the parents to read to each
    /// other.
    public func verificationCode() -> String { secret.verificationCode() }
}

/// The pairing, as this phone remembers it. From `data/sync/PairingStore.kt`.
///
/// **Split across two stores, deliberately.** The household id, the pairing
/// moment and the switches sit with the ordinary settings, which a backup
/// carries. The secret sits in the device-only store, which a backup does not —
/// so a record restored onto a new phone arrives with a household id and no key
/// to open it, exactly as it does on Android, where the secret survives the
/// backup as ciphertext wrapped by a Keystore key that stayed behind.
///
/// Neither half is ever in the database, so neither can travel inside a replica.
public final class PairingStore: @unchecked Sendable {

    private let settings: any DeviceLocalStore
    private let deviceOnly: any DeviceLocalStore

    public init(settings: any DeviceLocalStore, deviceOnly: any DeviceLocalStore) {
        self.settings = settings
        self.deviceOnly = deviceOnly
    }

    /// The pairing this phone can actually use, or `nil`.
    ///
    /// A household id with no secret behind it is not a transient failure: the
    /// secret was never in the backup, so this is what a record restored onto a
    /// *new* phone looks like (ADR-0011). Either way the household is only
    /// rejoinable by invitation, so the leftovers are cleared rather than left
    /// to half-say otherwise.
    ///
    /// That matters beyond tidiness. ``firstMergeDone()`` is one of the
    /// leftovers, and a phone that kept it while claiming to be unpaired would
    /// skip ADR-0008's question — keep both histories, or share from the pairing
    /// forward — the next time it was invited into a household, and adopt
    /// silently.
    public func current() -> Pairing? {
        guard let householdId = settings.string(forKey: Keys.household), !householdId.isEmpty
        else { return nil }
        guard let bytes = deviceOnly.data(forKey: Keys.secret),
              let secret = try? SyncSecret(bytes: bytes)
        else {
            unpair()
            return nil
        }
        return Pairing(
            householdId: householdId,
            secret: secret,
            pairedAt: settings.int64(forKey: Keys.pairedAt) ?? 0,
            shareStash: settings.bool(forKey: Keys.shareStash, default: true)
        )
    }

    public var isPaired: Bool { current() != nil }

    /// The secret goes down first, deliberately. The half-written state that
    /// matters is a household id with no secret behind it — ``current()`` reads
    /// that as a restored phone and *clears* the rest — so it is the one this
    /// must not be able to leave behind. A secret with no household id yet is
    /// inert, and the next save completes it.
    public func save(_ pairing: Pairing) {
        deviceOnly.set(pairing.secret.bytes, forKey: Keys.secret)
        settings.setInt64(pairing.pairedAt, forKey: Keys.pairedAt)
        settings.setBool(pairing.shareStash, forKey: Keys.shareStash)
        settings.setString(pairing.householdId, forKey: Keys.household)
    }

    /// Replaces the household's secret, keeping the household itself (ADR-0009).
    ///
    /// This is how a device is removed: possession of the secret *is* membership,
    /// so the only way to take it away is to change it and re-invite everyone who
    /// stays. Every other phone goes mute until it is re-invited — including one
    /// that was merely switched off at the wrong moment — and the phone being
    /// removed keeps whatever it already holds. Rotation stops what comes next;
    /// it cannot reach backwards.
    ///
    /// The verification code changes with the secret, which is the visible
    /// signal: anyone still showing the old code has not been re-invited yet.
    @discardableResult
    public func rotateSecret(now: Int64) -> Pairing? {
        guard var rotated = current() else { return nil }
        rotated.secret = SyncSecret.random()
        rotated.pairedAt = now
        save(rotated)
        return rotated
    }

    /// The stash switch (ADR-0008). Send-side only, and prospective: turning it
    /// off stops future replicas carrying the stash, it cannot reach into the
    /// other phone and take back what was already sent.
    public func setShareStash(_ share: Bool) {
        settings.setBool(share, forKey: Keys.shareStash)
    }

    /// Forget the partner. The data that already merged stays — it is the
    /// household's record, not a lease — and this phone simply stops being able
    /// to open or produce replicas for it.
    public func unpair() {
        settings.remove(Keys.household)
        settings.remove(Keys.pairedAt)
        settings.remove(Keys.shareStash)
        settings.remove(Keys.firstMergeDone)
        deviceOnly.remove(Keys.secret)
    }

    /// Whether a replica has ever been merged — the first one asks an extra
    /// question.
    public func firstMergeDone() -> Bool {
        settings.bool(forKey: Keys.firstMergeDone, default: false)
    }

    public func markFirstMergeDone() {
        settings.setBool(true, forKey: Keys.firstMergeDone)
    }

    /// The same names Android's preferences use, so the two apps describe one
    /// concept with one word even though neither can read the other's store.
    enum Keys {
        static let household = "sync_household_id"
        static let secret = "sync_secret"
        static let pairedAt = "sync_paired_at"
        static let shareStash = "sync_share_stash"
        static let firstMergeDone = "sync_first_merge_done"
    }
}
