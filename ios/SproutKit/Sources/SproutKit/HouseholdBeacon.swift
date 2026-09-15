import CryptoKit
import Foundation

/// How two phones of the same household recognise each other over the air
/// (ADR-0010, amended by ADR-0016).
///
/// A phone cannot simply advertise its household id: that would be a fixed
/// identifier, broadcast in the clear, that anyone nearby could log and follow
/// from one week to the next — a tracker attached to a family, which is exactly
/// what this app exists not to be.
///
/// So what goes out is derived: an HMAC of the household secret over the current
/// half-hour. It changes on its own every window, it is meaningless without the
/// secret, and a phone that holds the secret recognises it by computing the same
/// value.
public enum HouseholdBeacon {

    /// How long one value lasts. Short enough that the trail an observer could
    /// build is worthless; long enough that two phones with ordinary clock drift
    /// agree — and a listener accepts the previous window too.
    public static let windowMs: Int64 = 30 * 60 * 1000

    /// Bytes of HMAC kept for the service-data form.
    public static let valueBytes = 8

    /// Label mixed into the derivation of the advertised UUID, so the two forms
    /// cannot collide even though both are HMACs of the same secret and window.
    static let advertLabel = "sprout-adv-v1:"

    public static func window(at: Int64) -> Int64 {
        // Sprout has no dates before 1970. Floor and truncation agree here, and
        // the guard says so rather than leaving it to be noticed.
        precondition(at >= 0, "timestamps before the epoch are not a thing Sprout has")
        return at / windowMs
    }

    /// The 8-byte value Android advertises as BLE service data.
    public static func value(secret: SyncSecret, at: Int64) -> Data {
        valueForWindow(secret: secret, window: window(at: at))
    }

    /// The service UUID that replaces it, because iOS cannot advertise service
    /// data at all (ADR-0016).
    ///
    /// Raw 128 bits, with no RFC-4122 version or variant forced: `CBUUID` and
    /// `java.util.UUID` both take an arbitrary value, and spending six bits to
    /// look like a v4 buys nothing when a wrong guess simply fails to connect.
    public static func advertUuid(secret: SyncSecret, at: Int64) -> UUID {
        advertUuidForWindow(secret: secret, window: window(at: at))
    }

    /// The UUIDs a phone should be scanning for right now: this window and the
    /// last, so two phones a few minutes apart still find each other.
    public static func advertUuidsToScanFor(secret: SyncSecret, at: Int64) -> [UUID] {
        let now = window(at: at)
        return [
            advertUuidForWindow(secret: secret, window: now),
            advertUuidForWindow(secret: secret, window: now - 1),
        ]
    }

    /// Whether an advertisement seen at `at` belongs to this household.
    public static func matches(observed: Data, secret: SyncSecret, at: Int64) -> Bool {
        guard observed.count == valueBytes else { return false }
        let now = window(at: at)
        // Constant-time on both candidates, and deliberately without an early
        // return between them: a timing oracle here would leak whether a guess
        // is close, which is worth not offering.
        let current = constantTimeEquals(observed, valueForWindow(secret: secret, window: now))
        let previous = constantTimeEquals(observed, valueForWindow(secret: secret, window: now - 1))
        return current || previous
    }

    private static func valueForWindow(secret: SyncSecret, window: Int64) -> Data {
        Data(hmac(secret: secret, message: String(window)).prefix(valueBytes))
    }

    private static func advertUuidForWindow(secret: SyncSecret, window: Int64) -> UUID {
        let bytes = Array(hmac(secret: secret, message: advertLabel + String(window)).prefix(16))
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }

    /// The message is the window written as decimal ASCII — `"1"`, not eight
    /// bytes of integer. Both sides must agree on that or nothing ever matches.
    private static func hmac(secret: SyncSecret, message: String) -> Data {
        Data(HMAC<SHA256>.authenticationCode(
            for: Data(message.utf8),
            using: SymmetricKey(data: secret.bytes)
        ))
    }

    private static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var difference: UInt8 = 0
        for (x, y) in zip(a, b) { difference |= x ^ y }
        return difference == 0
    }
}
