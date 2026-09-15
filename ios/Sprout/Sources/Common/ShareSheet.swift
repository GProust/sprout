import SwiftUI

/// A URL that `sheet(item:)` can key on.
///
/// Shared by the report export and the sync exchange: both write a file and then
/// have nothing more to do with it, because handing it to the share sheet *is*
/// the transport.
struct SharedFile: Identifiable {
    let url: URL
    var id: String { url.path }
}

/// The system share sheet. The file leaves through whichever app the parent
/// picks — Sprout never sends it anywhere itself, and has no way to.
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    /// What the mail or message is called. Android passes `EXTRA_SUBJECT`; this
    /// is the same thing under a different name.
    var subject: String? = nil

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        if let subject {
            controller.setValue(subject, forKey: "subject")
        }
        return controller
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Something Android has and this app does not yet, said out loud.
///
/// The alternative is a control that stores a preference and does nothing, which
/// reads as a promise.
///
/// The note is **deliberately not translated**. The string catalog is generated
/// from Android's resources and CI checks the two match, so a key that exists
/// only here cannot be added without inventing seven translations nobody wrote.
/// English is the honest option for a line that should disappear when the
/// feature lands.
struct NotYetOnIOS: View {
    let title: String
    let detail: String

    init(_ title: String, _ detail: String) {
        self.title = title
        self.detail = detail
    }

    var body: some View {
        HStack(alignment: .top, spacing: Spacing.snug) {
            Image(systemName: "hammer.fill")
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .font(.footnote)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(SproutColor.onSurfaceVariant)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
                Text("Not on iPhone yet.")
                    .font(.caption.italic())
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            }
        }
    }
}
