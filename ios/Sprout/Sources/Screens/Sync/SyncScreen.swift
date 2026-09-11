import SproutData
import SwiftUI
import UniformTypeIdentifiers

/// Sharing one baby's record with everyone looking after them (ADR-0007,
/// ADR-0008, ADR-0009). From `ui/sync/SyncScreen.kt`.
///
/// Usually that is the other parent; it can equally be a grandparent who has the
/// baby twice a week. There is no account and no server: Sprout writes a sealed
/// file and hands it to the share sheet, and the parents move it through
/// whatever channel they already use. What this screen mostly does is explain,
/// because the honest version of "no server" is that the data goes directly from
/// one phone to the other.
///
/// **Android's automatic exchange is not offered here yet** — see
/// ``SyncViewModel``. It is one line on the screen rather than a switch,
/// because a switch that stores a preference and meets nobody is a promise.
struct SyncScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: SyncViewModel?
    @State private var picking = false
    @State private var confirmingUnpair = false
    @State private var deviceToRemove: HouseholdDevice?

    var body: some View {
        Group {
            if let model { content(model) } else { Color.clear }
        }
        .navigationTitle(Str.t("screen_sync"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? sprout.makeSyncViewModel()
            self.model = model
            await consumePendingFile(model)
        }
        // And again while the screen is already open — a second file arriving
        // does not rebuild the view, so `.task` alone would silently drop it.
        .onChange(of: sprout.pendingSyncFile) { _, url in
            guard url != nil, let model else { return }
            Task { await consumePendingFile(model) }
        }
    }

    /// Opens a file another app handed Sprout, and clears it so a second tap on
    /// the same file works.
    private func consumePendingFile(_ model: SyncViewModel) async {
        guard let url = sprout.pendingSyncFile else { return }
        sprout.pendingSyncFile = nil
        await model.open(url)
    }

    @ViewBuilder
    private func content(_ model: SyncViewModel) -> some View {
        Form {
            if model.busy {
                Section {
                    // Bare, as Android's linear indicator is. There is no
                    // "working" string in the sync set, and inventing a key here
                    // would fail the check that the catalog matches Android's.
                    ProgressView().frame(maxWidth: .infinity)
                }
            }

            if let pairing = model.pairing {
                verificationCode(pairing)
                automaticExchange
                byHand(model)
                stash(model, pairing: pairing)
                household(model)
                leaving(model, pairing: pairing)
            } else {
                notPaired(model)
            }
        }
        .sproutStyle()
        // The share sheet is the whole transport: hand the file over and let the
        // parent choose who carries it.
        .sheet(item: Binding(
            get: { model.share },
            set: { if $0 == nil { model.share = nil } }
        )) { file in
            ShareSheet(url: file.url, subject: Str.t(model.shareSubjectKey))
        }
        // One document type, because an invitation and a replica are told apart
        // by looking at them rather than by their name (see `SyncFiles`).
        .fileImporter(
            isPresented: $picking,
            allowedContentTypes: [SyncFiles.contentType, .data]
        ) { result in
            guard case .success(let url) = result else { return }
            Task { await model.open(url) }
        }
        .alert(
            title(for: model.outcome),
            isPresented: Binding(
                get: { model.outcome != nil },
                set: { if !$0 { model.outcome = nil } }
            ),
            presenting: model.outcome
        ) { outcome in
            buttons(for: outcome, model: model)
        } message: { outcome in
            Text(bodyText(for: outcome))
        }
        .confirmationDialog(
            Str.t("sync_unpair"),
            isPresented: $confirmingUnpair,
            titleVisibility: .visible
        ) {
            Button(Str.t("sync_unpair"), role: .destructive) { model.unpair() }
            Button(Str.t("action_cancel"), role: .cancel) {}
        } message: {
            Text(Str.t("sync_unpair_confirm"))
        }
        .confirmationDialog(
            Str.t("sync_remove_title"),
            isPresented: Binding(
                get: { deviceToRemove != nil },
                set: { if !$0 { deviceToRemove = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(Str.t("sync_device_remove"), role: .destructive) {
                if let device = deviceToRemove { model.removeDevice(device.deviceId) }
                deviceToRemove = nil
            }
            Button(Str.t("action_cancel"), role: .cancel) { deviceToRemove = nil }
        } message: {
            Text(Str.t("sync_remove_body"))
        }
    }

    // MARK: - Before there is a household

    @ViewBuilder
    private func notPaired(_ model: SyncViewModel) -> some View {
        Section {
            VStack(alignment: .leading, spacing: Spacing.tight) {
                Text(Str.t("sync_intro_title")).font(.headline)
                Text(Str.t("sync_intro_body"))
                    .font(.callout)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            }
            .padding(.vertical, Spacing.hairline)
        }

        Section {
            Button(Str.t("sync_invite")) { model.createInvitation(shareStash: true) }
                .accessibilityIdentifier("sync-invite")
            Button(Str.t("sync_open_invitation")) { picking = true }
        } footer: {
            Text(Str.t("sync_channel_warning"))
        }
    }

    // MARK: - The household

    /// The six characters both phones show. Reading them to each other is the
    /// moment that turns "a file arrived" into "we are paired with *each other*".
    @ViewBuilder
    private func verificationCode(_ pairing: Pairing) -> some View {
        Section {
            VStack(spacing: Spacing.tight) {
                Text(pairing.verificationCode())
                    .font(.system(size: 32, weight: .semibold, design: .monospaced))
                    .foregroundStyle(SproutColor.onSurface)
                    // Read aloud at 3 a.m., so it is one item to VoiceOver rather
                    // than six letters run together into a word it will mangle.
                    .accessibilityLabel(
                        pairing.verificationCode().map { String($0) }.joined(separator: " ")
                    )
                Text(Str.t("sync_code_hint"))
                    .font(.caption)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, Spacing.tight)
        } header: {
            Text(Str.t("sync_code_title"))
        }
    }

    private var automaticExchange: some View {
        Section {
            NotYetOnIOS(Str.t("sync_nearby_title"), Str.t("sync_nearby_desc"))
        }
    }

    @ViewBuilder
    private func byHand(_ model: SyncViewModel) -> some View {
        Section {
            Button(Str.t("sync_send")) { Task { await model.exportReplica() } }
                .accessibilityIdentifier("sync-send")
                .disabled(model.busy)
            Button(Str.t("sync_receive")) { picking = true }
                .disabled(model.busy)
        } header: {
            Text(Str.t("sync_by_hand_title"))
        } footer: {
            Text(Str.t("sync_exchange_hint"))
        }
    }

    @ViewBuilder
    private func stash(_ model: SyncViewModel, pairing: Pairing) -> some View {
        Section {
            Toggle(isOn: Binding(
                get: { pairing.shareStash },
                set: { model.setShareStash($0) }
            )) {
                Text(Str.t("sync_stash_title"))
            }
        } footer: {
            Text(pairing.shareStash ? Str.t("sync_stash_desc") : Str.t("sync_stash_off_note"))
        }
    }

    /// The household's other phones, as they have introduced themselves. A list
    /// of who has been heard from — not who is allowed in; the key is what
    /// decides that (ADR-0009).
    @ViewBuilder
    private func household(_ model: SyncViewModel) -> some View {
        Section {
            if model.devices.isEmpty {
                Text(Str.t("sync_devices_empty"))
                    .font(.callout)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            } else {
                ForEach(model.devices) { device in
                    HStack {
                        Text(device.name.isEmpty ? Str.t("sync_device_unnamed") : device.name)
                        Spacer()
                        Button(Str.t("sync_device_remove")) { deviceToRemove = device }
                            .font(.callout)
                    }
                }
            }
        } header: {
            Text(Str.t("sync_devices_title"))
        }
    }

    @ViewBuilder
    private func leaving(_ model: SyncViewModel, pairing: Pairing) -> some View {
        Section {
            Button(Str.t("sync_invite_again")) {
                model.createInvitation(shareStash: pairing.shareStash)
            }
            Button(Str.t("sync_unpair"), role: .destructive) { confirmingUnpair = true }
        }
    }

    // MARK: - Saying what happened

    @ViewBuilder
    private func buttons(for outcome: SyncOutcome, model: SyncViewModel) -> some View {
        // The one outcome that asks rather than tells (ADR-0008). Both answers
        // re-open the same file; neither is a cancel, because the parent has
        // already chosen to merge — and the question is asked once, ever.
        if case .askAboutHistories(let url) = outcome {
            Button(Str.t("sync_histories_keep_all")) {
                Task { await model.open(url, historyChoice: .keepEverything) }
            }
            Button(Str.t("sync_histories_from_pairing")) {
                Task { await model.open(url, historyChoice: .fromPairing) }
            }
        } else {
            Button(Str.t("action_ok")) { model.outcome = nil }
        }
    }

    private func title(for outcome: SyncOutcome?) -> String {
        switch outcome {
        case .paired: return Str.t("sync_paired_title")
        case .merged: return Str.t("sync_merged_title")
        case .secretRotated: return Str.t("sync_rotated_title")
        case .failed: return Str.t("sync_failed_title")
        case .askAboutHistories: return Str.t("sync_histories_title")
        case nil: return ""
        }
    }

    private func bodyText(for outcome: SyncOutcome) -> String {
        switch outcome {
        case .paired(let partnerName, let code):
            return partnerName.isEmpty
                ? Str.t("sync_paired_body", code)
                : Str.t("sync_paired_body_named", partnerName, code)
        case .merged(let summary):
            return mergeSummaryText(summary)
        case .secretRotated(let code):
            return Str.t("sync_rotated_body", code)
        case .failed(let key):
            return Str.t(key)
        case .askAboutHistories:
            return Str.t("sync_histories_body")
        }
    }
}

/// What the merge did, in a sentence.
///
/// "Nothing new" is a real and frequent outcome — the parents exchange files more
/// often than they both log — and saying it plainly is what distinguishes a merge
/// that worked from one that silently failed.
func mergeSummaryText(_ summary: MergeSummary) -> String {
    guard summary.changed else { return Str.t("sync_merged_nothing_new") }
    var parts: [String] = []
    if summary.added > 0 { parts.append(Str.t("sync_merged_added", summary.added)) }
    if summary.updated > 0 { parts.append(Str.t("sync_merged_updated", summary.updated)) }
    if summary.deleted > 0 { parts.append(Str.t("sync_merged_deleted", summary.deleted)) }
    return parts.joined(separator: ", ")
}
