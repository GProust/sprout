import Foundation

/// How a phone tells another which L2CAP channel to dial (ADR-0016).
///
/// A `CBL2CAPChannel` is reached by its **PSM**, a number the Bluetooth stack
/// picks when the listener publishes the channel. RFCOMM — which is what the
/// Android app used before this, and which iOS cannot speak at all — needed no
/// answer to that, because a service record carries a fixed UUID and the stack
/// looks the channel up. L2CAP has no such directory, so the number is published
/// on a single read-only GATT characteristic on the service the listener
/// advertises.
///
/// Nothing fixed reaches the air because of it. A GATT service is only visible
/// to a phone that has already connected, and connecting means having matched
/// the rotating ``HouseholdBeacon/advertUuid(secret:at:)`` first.
///
/// This file is the part of that both platforms have to agree on, and nothing
/// else: the radio around it is still to be written on this side.
public enum L2capPsm {

    /// The characteristic that answers "which PSM?".
    ///
    /// Fixed on both platforms and pinned by `spec/vectors/l2cap.json`: two apps
    /// that derived the same advertisement but read different characteristics
    /// would connect and then find nothing, which is the silent failure `spec/`
    /// exists to prevent.
    public static let characteristicUuid = UUID(uuidString: "5F9B3A70-6D1E-4B6A-9C4E-2F7D8A1B0C34")!

    /// The value's length: an unsigned 16-bit integer, big-endian.
    public static let bytes = 2

    /// The characteristic's value for a channel published on `psm`.
    public static func encode(_ psm: UInt16) -> Data {
        precondition(psm > 0, "zero is not a channel")
        return Data([UInt8(psm >> 8), UInt8(psm & 0xFF)])
    }

    /// Reads a characteristic's value back.
    ///
    /// Nil rather than a throw for everything unusable — absent, the wrong
    /// length, or zero: this is bytes from another phone, and the only sensible
    /// answer to "that is not a PSM" is to leave that phone for the next window
    /// rather than to fail the whole exchange.
    public static func decode(_ value: Data?) -> UInt16? {
        guard let value, value.count == bytes else { return nil }
        let start = value.startIndex
        let psm = UInt16(value[start]) << 8 | UInt16(value[start + 1])
        return psm > 0 ? psm : nil
    }
}
