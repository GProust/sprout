import SproutKit
import SwiftUI

/// The iOS app (ADR-0015).
///
/// A shell, on purpose. What has landed so far is `SproutKit` — the formats two
/// phones exchange, checked against `spec/vectors/` on every run — because that
/// is the part whose correctness a Linux CI runner can actually prove. The
/// screens come next, and they need a Mac to look at: a SwiftUI view that
/// compiles is not a SwiftUI view that reads well at 3 a.m. one-handed, and
/// nobody should pretend otherwise from a machine that cannot render it.
///
/// The order is deliberate rather than convenient. Getting the wire format wrong
/// is the expensive mistake — two phones that quietly stop syncing, and a parent
/// who finds out weeks later — and it is the one mistake that is cheapest to
/// catch before any UI exists to distract from it.
@main
struct SproutApp: App {
    var body: some Scene {
        WindowGroup {
            GroundworkView()
        }
    }
}

/// Stands in for the dashboard until the screens land, and does one useful
/// thing on the way: proves at runtime that SproutKit is linked and agrees with
/// the specification, rather than only in a test target.
struct GroundworkView: View {

    private let check = SelfCheck.run()

    var body: some View {
        VStack(spacing: 16) {
            Text("🌱")
                .font(.system(size: 64))
            Text("Sprout")
                .font(.largeTitle.weight(.semibold))
            Text(check.summary)
                .font(.callout)
                .foregroundStyle(check.passed ? .secondary : Color.red)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }
}

/// A round trip through the sealed-replica format, run on launch.
///
/// Not a substitute for `SproutKitTests` — it uses a throwaway secret and
/// proves only that the pieces are wired together in a built app, where the
/// tests prove they match the format.
enum SelfCheck {

    struct Result {
        let passed: Bool
        let summary: String
    }

    static func run() -> Result {
        do {
            let secret = SyncSecret.random()
            let payload = Data(#"{"formatVersion":1}"#.utf8)
            let opened = try SyncCrypto.open(try SyncCrypto.seal(payload, secret: secret), secret: secret)
            guard opened == payload else {
                return Result(passed: false, summary: "SproutKit sealed a replica it could not reopen.")
            }
            return Result(
                passed: true,
                summary: "SproutKit is linked and the replica format round-trips.\nThe screens are next."
            )
        } catch {
            return Result(passed: false, summary: "SproutKit failed its self-check: \(error)")
        }
    }
}

#Preview {
    GroundworkView()
}
