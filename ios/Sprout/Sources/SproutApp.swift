import SproutData
import SwiftUI
import UIKit

/// The iOS app (ADR-0015).
///
/// The screens land in batches, verified by CI and reviewed as screenshots. The
/// shell is ``RootView``, and every screen behind it is now a real one — the
/// placeholder that named the missing ones is gone, which is what it was for.
@main
struct SproutApp: App {

    /// The one thing that has to happen before launch finishes: the
    /// notification delegate. See ``SproutAppDelegate``.
    @UIApplicationDelegateAdaptor(SproutAppDelegate.self) private var appDelegate

    @Environment(\.scenePhase) private var scenePhase
    @State private var environment: AppEnvironment?
    @State private var failure: String?

    var body: some Scene {
        WindowGroup {
            Group {
                if let environment {
                    RootView().environment(\.sprout, environment)
                } else if let failure {
                    DatabaseFailureView(message: failure)
                } else {
                    // The database opens in milliseconds; this is here so the
                    // first frame is never an empty white window.
                    ProgressView().sproutStyle()
                }
            }
            .task {
                guard environment == nil, failure == nil else { return }
                do {
                    #if DEBUG
                    // The screenshot run gets a fixed in-memory database and a
                    // frozen clock. Compiled out of Release entirely — see
                    // ScreenshotSeed.
                    if ScreenshotSeed.isRequested {
                        environment = try ScreenshotSeed.environment()
                        return
                    }
                    #endif
                    environment = try AppEnvironment.onDisk()
                    // A *Give a dose* tapped from the lock screen can launch the
                    // app from cold; the response is waiting by the time the
                    // database is open.
                    if let environment {
                        MedicineNotificationActions.shared.attach(environment)
                    }
                } catch {
                    // ADR-0002: there is no server copy of any of this, so a
                    // database that will not open is not something to paper
                    // over with an empty screen.
                    failure = String(describing: error)
                }
            }
            // A `.sprout` file another app handed us — from Mail, Messages, or
            // wherever the parents already talk to each other (ADR-0008). It is
            // the same door the manifest declares on Android, and it opens onto
            // the same screen: everything about the file is decided there, by
            // looking at its bytes.
            .onOpenURL { url in
                environment?.pendingSyncFile = url
            }
            // The two moments the reminder schedule is rebuilt (ADR-0019).
            //
            // They bracket every write there is, which is why there is no hook
            // threaded through the screens that write: going to the background
            // re-arms from the feed just logged, and coming back catches a
            // household exchange, a passing midnight, and the bounded schedules
            // that need topping up.
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active || phase == .background else { return }
                guard let environment else { return }
                Task {
                    await ReminderScheduler.rebuild(
                        repository: environment.repository,
                        settings: environment.settingsStore
                    )
                }
            }
        }
    }
}

/// The only reason this app has a `UIApplicationDelegate` at all.
///
/// `UNUserNotificationCenter`'s delegate has to be set before launching
/// finishes, or a notification action tapped while the app was not running is
/// delivered to nobody — and that action is a dose the parent has already
/// given. SwiftUI's `.task` runs too late for that; this does not.
final class SproutAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        MainActor.assumeIsolated { MedicineNotificationActions.shared.register() }
        return true
    }
}

/// Shown when the database cannot be opened.
///
/// It says what happened rather than pretending the app is empty: an empty
/// dashboard and a database that failed to open look identical to a parent, and
/// only one of them means "your history is still there".
///
/// Deliberately **not** translated. Android has no counterpart — Room throws and
/// the process goes — so there is no key for this in the shared catalog, and
/// inventing one would mean seven translations I cannot write. English here is
/// the honest option; if this screen ever proves reachable in practice it earns
/// a real string on both sides.
struct DatabaseFailureView: View {
    let message: String

    var body: some View {
        VStack(spacing: Spacing.regular) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundStyle(SproutColor.danger)
            Text("Sprout could not open its database.")
                .font(.headline)
                .multilineTextAlignment(.center)
            Text(message)
                .font(.footnote)
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
        }
        .padding(Spacing.section)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .sproutStyle()
    }
}

#Preview {
    RootView()
}
