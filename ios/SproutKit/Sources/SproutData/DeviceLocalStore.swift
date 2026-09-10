import Foundation
import Security

/// Somewhere to keep a handful of small device-local values.
///
/// Android has two preferences files and picks between them by what a backup
/// should carry (ADR-0011); iOS has no per-key backup exclusion, so the same
/// distinction is made by *which store* a value goes in — see
/// ``KeychainStore``. The protocol exists so that both, and a third that only
/// remembers, are the same shape to everything above.
public protocol DeviceLocalStore: AnyObject, Sendable {
    func data(forKey key: String) -> Data?
    func set(_ value: Data?, forKey key: String)
}

public extension DeviceLocalStore {
    func string(forKey key: String) -> String? {
        data(forKey: key).flatMap { String(data: $0, encoding: .utf8) }
    }

    func setString(_ value: String?, forKey key: String) {
        set(value?.data(using: .utf8), forKey: key)
    }

    func int64(forKey key: String) -> Int64? { string(forKey: key).flatMap(Int64.init) }

    func setInt64(_ value: Int64?, forKey key: String) {
        setString(value.map(String.init), forKey: key)
    }

    /// Absent means the default, which is how a setting added in a later release
    /// behaves on a phone that has never been asked about it.
    func bool(forKey key: String, default fallback: Bool) -> Bool {
        switch string(forKey: key) {
        case "true": return true
        case "false": return false
        default: return fallback
        }
    }

    func setBool(_ value: Bool, forKey key: String) {
        setString(value ? "true" : "false", forKey: key)
    }

    func remove(_ key: String) { set(nil, forKey: key) }
}

/// The ordinary settings, which a backup carries — Android's `settings.xml`.
public final class UserDefaultsStore: DeviceLocalStore, @unchecked Sendable {

    private let defaults: UserDefaults
    private let prefix: String

    public init(defaults: UserDefaults = .standard, prefix: String = "sprout.") {
        self.defaults = defaults
        self.prefix = prefix
    }

    public func data(forKey key: String) -> Data? { defaults.data(forKey: prefix + key) }

    public func set(_ value: Data?, forKey key: String) {
        if let value {
            defaults.set(value, forKey: prefix + key)
        } else {
            defaults.removeObject(forKey: prefix + key)
        }
    }
}

/// The values that must not travel to a new phone — Android's `device.xml`, and
/// the Keystore behind the household secret (ADR-0011, ADR-0018).
///
/// The keychain is used here for its **accessibility flag**, not only for its
/// secrecy: an item stored `…ThisDeviceOnly` is left out of both the iCloud
/// backup and the cable transfer during new-phone setup. `UserDefaults` has no
/// equivalent — a backup takes the whole property list or none of it — so a
/// value that describes *this handset* has nowhere else to go.
///
/// `WhenUnlocked` rather than `AfterFirstUnlock` because nothing here is read
/// while the phone is locked: the exchange happens when the app is open
/// (ADR-0010), which means someone is holding an unlocked phone.
public final class KeychainStore: DeviceLocalStore, @unchecked Sendable {

    private let service: String

    public init(service: String = "com.gproust.sprout.device") {
        self.service = service
    }

    public func data(forKey key: String) -> Data? {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else { return nil }
        return item as? Data
    }

    public func set(_ value: Data?, forKey key: String) {
        let query = baseQuery(key)
        guard let value else {
            SecItemDelete(query as CFDictionary)
            return
        }

        // Update first, add only if there was nothing there. Deleting and
        // re-adding would be simpler and would lose the value outright if the
        // process died between the two.
        let updated = SecItemUpdate(
            query as CFDictionary,
            [kSecValueData as String: value] as CFDictionary
        )
        if updated == errSecItemNotFound {
            var insert = query
            insert[kSecValueData as String] = value
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            SecItemAdd(insert as CFDictionary, nil)
        }
    }

    private func baseQuery(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }
}

/// A store that only remembers, for tests.
///
/// The keychain needs an entitlement the test bundle does not have, so a test
/// that reached the real one would fail on the runner rather than say anything
/// about the code. This is the same seam Android's `SecretVault` interface
/// opens, for the same reason.
public final class InMemoryStore: DeviceLocalStore, @unchecked Sendable {

    private let lock = NSLock()
    private var values: [String: Data] = [:]

    public init() {}

    public func data(forKey key: String) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return values[key]
    }

    public func set(_ value: Data?, forKey key: String) {
        lock.lock()
        defer { lock.unlock() }
        values[key] = value
    }
}
