import SproutData
import SwiftUI

/// The iOS app (ADR-0015).
///
/// The screens land in batches, verified by CI and reviewed as screenshots. The
/// shell is ``RootView``; the screens still to come are named by ``NotYetPorted``
/// rather than left blank, so a capture says which is missing.
@main
struct SproutApp: App {

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
                } catch {
                    // ADR-0002: there is no server copy of any of this, so a
                    // database that will not open is not something to paper
                    // over with an empty screen.
                    failure = String(describing: error)
                }
            }
        }
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
