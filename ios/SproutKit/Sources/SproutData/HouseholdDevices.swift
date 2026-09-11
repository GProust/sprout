import Foundation

/// A phone this one has heard from, as it last introduced itself.
public struct HouseholdDevice: Equatable, Sendable, Identifiable {
    public var deviceId: String
    /// The sender's own name, or empty for a replica written before ADR-0009.
    public var name: String
    public var lastSeen: Int64

    public var id: String { deviceId }

    public init(deviceId: String, name: String, lastSeen: Int64) {
        self.deviceId = deviceId
        self.name = name
        self.lastSeen = lastSeen
    }
}

/// The other phones in the household, remembered as their replicas arrive
/// (ADR-0009). From `data/sync/HouseholdDevices.kt`.
///
/// This is a **courtesy list, not access control**. Membership is possession of
/// the shared secret; a device that never sends a replica never appears here,
/// and forgetting one here does not shut it out. Removing someone for real means
/// rotating the secret — ``PairingStore/rotateSecret(now:)``.
///
/// Kept in the settings rather than the database, so it cannot travel inside a
/// replica: who *this* phone has heard from is not a fact about the baby.
public final class HouseholdDevices: @unchecked Sendable {

    private let settings: any DeviceLocalStore

    public init(settings: any DeviceLocalStore) {
        self.settings = settings
    }

    public func all() -> [HouseholdDevice] {
        guard let raw = settings.data(forKey: Self.key) else { return [] }
        let parsed = try? JSONSerialization.jsonObject(with: raw)
        guard let array = parsed as? [[String: Any]] else { return [] }

        return array.compactMap { entry in
            guard let deviceId = entry["deviceId"] as? String,
                  let lastSeen = (entry["lastSeen"] as? NSNumber)?.int64Value
            else { return nil }
            return HouseholdDevice(
                deviceId: deviceId,
                name: entry["name"] as? String ?? "",
                lastSeen: lastSeen
            )
        }
        .sorted { $0.lastSeen > $1.lastSeen }
    }

    /// Records that a replica arrived from `deviceId`, keeping the newest name it
    /// gave. A device that renames itself is the same device; a blank name never
    /// overwrites one we already know.
    public func seen(deviceId: String, name: String, at: Int64) {
        guard !deviceId.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let known = all()
        let previousName = known.first { $0.deviceId == deviceId }?.name ?? ""
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        var devices = known.filter { $0.deviceId != deviceId }
        devices.append(
            HouseholdDevice(
                deviceId: deviceId,
                name: trimmed.isEmpty ? previousName : name,
                lastSeen: at
            )
        )
        write(devices)
    }

    /// Drops a device from the list. Rotating the secret is what actually removes
    /// it.
    public func forget(deviceId: String) {
        write(all().filter { $0.deviceId != deviceId })
    }

    public func clear() { settings.remove(Self.key) }

    private func write(_ devices: [HouseholdDevice]) {
        let array: [[String: Any]] = devices.map { device in
            ["deviceId": device.deviceId, "name": device.name, "lastSeen": device.lastSeen]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: array) else { return }
        settings.set(data, forKey: Self.key)
    }

    private static let key = "sync_household_devices"
}
