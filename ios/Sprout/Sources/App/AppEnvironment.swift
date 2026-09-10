import Foundation
import GRDB
import SproutData
import SwiftUI

/// What the whole app hangs off: one database, one repository.
///
/// The Android side keeps these on the `Application` and hands them to view
/// models through a factory. Here it is one object in the environment, for the
/// same reason — there is exactly one database, and a second one opened by
/// accident would be a second set of a baby's history.
@Observable
final class AppEnvironment {

    let repository: SproutRepository
    /// The merge, and the replicas that feed it (ADR-0007).
    let engine: SyncEngine
    /// Who this phone is paired with, and the phones it has heard from.
    let pairingStore: PairingStore
    let householdDevices: HouseholdDevices
    /// The store a backup leaves alone (ADR-0018). Held here as well as inside
    /// `pairingStore` because this device's own id lives in it, and a replica
    /// carries that id.
    let deviceStore: any DeviceLocalStore
    /// The ordinary settings, which a backup carries (ADR-0018) — the reminder
    /// switches live here, and so does the household id.
    let settingsStore: any DeviceLocalStore

    /// A file another app has just handed Sprout, waiting to be opened.
    ///
    /// It lands here rather than in the sync screen's model because it arrives
    /// before that screen exists: the app has to be pushed to it first. Cleared
    /// by whoever consumes it, so a second tap on the same file works.
    var pendingSyncFile: URL?

    private init(
        repository: SproutRepository,
        engine: SyncEngine,
        settings: any DeviceLocalStore,
        deviceStore: any DeviceLocalStore
    ) {
        self.repository = repository
        self.engine = engine
        self.deviceStore = deviceStore
        self.settingsStore = settings
        self.pairingStore = PairingStore(settings: settings, deviceOnly: deviceStore)
        self.householdDevices = HouseholdDevices(settings: settings)
    }

    @MainActor
    func makeSyncViewModel() -> SyncViewModel {
        SyncViewModel(
            repository: repository,
            engine: engine,
            pairingStore: pairingStore,
            householdDevices: householdDevices,
            deviceStore: deviceStore
        )
    }

    /// The real thing, on disk.
    ///
    /// In Application Support rather than Documents: this is the app's own
    /// store, not a folder of files the parent manages, and it should not show
    /// up in the Files app.
    static func onDisk() throws -> AppEnvironment {
        let directory = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let queue = try SproutDatabase.open(atPath: directory.appendingPathComponent("sprout.db").path)
        return AppEnvironment(
            repository: SproutRepository(database: queue),
            engine: SyncEngine(database: queue),
            // The two stores of ADR-0018, chosen by whether a backup should
            // carry the value.
            settings: UserDefaultsStore(),
            deviceStore: KeychainStore()
        )
    }

    /// An empty in-memory app, for previews and for the screenshot run.
    ///
    /// In-memory stores as well as an in-memory database: a preview that wrote
    /// to the real keychain would leave a household secret behind on whatever
    /// machine rendered it, and a screenshot run has to start from the same
    /// state every time or the captures are not a set.
    static func inMemory() throws -> AppEnvironment {
        let queue = try SproutDatabase.inMemory()
        return AppEnvironment(
            repository: SproutRepository(database: queue),
            engine: SyncEngine(database: queue),
            settings: InMemoryStore(),
            deviceStore: InMemoryStore()
        )
    }
}

private struct AppEnvironmentKey: EnvironmentKey {
    // Force-try: an in-memory database with no migrations to run cannot fail,
    // and a preview that traps here would be saying something true and useful.
    static let defaultValue: AppEnvironment = try! .inMemory()
}

extension EnvironmentValues {
    var sprout: AppEnvironment {
        get { self[AppEnvironmentKey.self] }
        set { self[AppEnvironmentKey.self] = newValue }
    }
}

// MARK: - Observing the repository

/// Mirrors one of the repository's streams into an observable property.
///
/// Room's `Flow` collected by a Compose screen becomes GRDB's observation
/// consumed by a `Task` here. The task is tied to the view's lifetime by
/// `.task {}`, so it stops when the screen goes away — the equivalent of
/// `WhileSubscribed` on the other side.
@MainActor
func observe<T>(
    _ sequence: AsyncValueObservation<T>,
    into assign: @MainActor @escaping (T) -> Void
) async {
    do {
        for try await value in sequence {
            assign(value)
        }
    } catch {
        // A failed observation means the database went away underneath us,
        // which on iOS means the app is being torn down. Nothing useful to show
        // a parent, and nothing to recover.
    }
}

/// The clock, in the one place a screenshot run can freeze it.
///
/// Every screen reads "now" through this rather than calling `Date()`, so the
/// App Store captures are reproducible: a card that says "5 min ago" has to say
/// that in every language's screenshot, or the set does not look like one app.
enum Clock {
    nonisolated(unsafe) static var now: () -> Int64 = {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    static var millis: Int64 { now() }
}
