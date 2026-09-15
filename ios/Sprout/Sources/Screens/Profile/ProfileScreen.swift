import SproutData
import SwiftUI

/// The babies Sprout is tracking, from `ui/profile/ProfileScreen.kt`.
///
/// Several are supported — twins, or siblings over time — and the two ways of
/// removing one are deliberately different (ADR-0007):
///
/// - **Stop tracking** takes a baby out of the rotation and keeps every row. It
///   is the reversible one, and the one nearly everybody wants.
/// - **Delete** erases the rows and keeps only their uids, so the deletion
///   travels to the other phones in the household without the data lingering
///   here. It cannot be undone, and the dialog says so.
@Observable
@MainActor
final class ProfileViewModel {
    var babies: [Baby] = []
    var archived: [Baby] = []
    var activeBabyId: Int64?

    private let repository: SproutRepository

    init(repository: SproutRepository) {
        self.repository = repository
    }

    func observeEverything() async {
        let repository = self.repository
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                await observe(repository.babies) { [weak self] in self?.babies = $0 }
            }
            group.addTask {
                await observe(repository.archivedBabies) { [weak self] in self?.archived = $0 }
            }
            group.addTask {
                await observe(repository.parentProfile) { [weak self] in
                    self?.activeBabyId = $0?.activeBabyId
                }
            }
        }
    }

    func add(name: String, birthDate: Int64) {
        _ = try? repository.addBaby(name: name, birthDate: birthDate)
    }

    func update(_ baby: Baby, name: String, birthDate: Int64) {
        var updated = baby
        updated.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        updated.birthDate = birthDate
        try? repository.updateBaby(updated)
    }

    func setActive(_ baby: Baby) {
        guard let id = baby.id else { return }
        try? repository.setActiveBaby(id: id)
    }

    func archive(_ baby: Baby) {
        guard let id = baby.id else { return }
        try? repository.archiveBaby(id: id)
    }

    func restore(_ baby: Baby) {
        guard let id = baby.id else { return }
        try? repository.restoreBaby(id: id)
    }

    func delete(_ baby: Baby) {
        guard let id = baby.id else { return }
        try? repository.deleteBaby(id: id)
    }
}

/// Which baby the editor is open on: a new one, or an existing one.
private enum BabyEditing: Identifiable {
    case new
    case existing(Baby)

    var id: Int64 { baby?.id ?? -1 }

    var baby: Baby? {
        if case .existing(let baby) = self { return baby }
        return nil
    }
}

struct ProfileScreen: View {
    @Environment(\.sprout) private var sprout
    @State private var model: ProfileViewModel?
    @State private var editing: BabyEditing?
    @State private var pendingDelete: Baby?

    var body: some View {
        Group {
            if let model { content(model) } else { Color.clear }
        }
        .navigationTitle(Str.t("screen_babies"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? ProfileViewModel(repository: sprout.repository)
            self.model = model
            await model.observeEverything()
        }
    }

    @ViewBuilder
    private func content(_ model: ProfileViewModel) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.snug) {
                if model.babies.isEmpty {
                    Text(Str.t("babies_empty"))
                        .font(.callout)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                        .padding(.vertical, Spacing.tight)
                }

                ForEach(model.babies) { baby in
                    BabyCard(
                        baby: baby,
                        isActive: baby.id == model.activeBabyId,
                        onMakeActive: { model.setActive(baby) },
                        onEdit: { editing = .existing(baby) },
                        onArchive: { model.archive(baby) },
                        onDelete: { pendingDelete = baby }
                    )
                }

                Button { editing = .new } label: {
                    Label(Str.t("baby_add"), systemImage: "plus")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Spacing.snug)
                }
                .buttonStyle(.bordered)

                if !model.archived.isEmpty {
                    VStack(alignment: .leading, spacing: Spacing.tight) {
                        Text(Str.t("babies_not_tracking"))
                            .font(.headline)
                        Text(Str.t("babies_not_tracking_help"))
                            .font(.caption)
                            .foregroundStyle(SproutColor.onSurfaceVariant)

                        ForEach(model.archived) { baby in
                            ArchivedRow(
                                baby: baby,
                                onRestore: { model.restore(baby) },
                                onDelete: { pendingDelete = baby }
                            )
                        }
                    }
                    .padding(.top, Spacing.section)
                }
            }
            .padding(Spacing.regular)
        }
        .sproutStyle()
        .sheet(item: $editing) { editing in
            BabyEditor(baby: editing.baby) { name, birthDate in
                if let baby = editing.baby {
                    model.update(baby, name: name, birthDate: birthDate)
                } else {
                    model.add(name: name, birthDate: birthDate)
                }
                self.editing = nil
            }
        }
        // Not `confirmDelete`: this one is the irreversible path and has to say
        // what it takes with it, by name.
        .confirmDelete(
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            title: Str.t("baby_delete_title", pendingDelete?.name ?? ""),
            message: Str.t("baby_delete_body", pendingDelete?.name ?? "")
        ) {
            if let baby = pendingDelete { model.delete(baby) }
            pendingDelete = nil
        }
    }
}

private struct BabyCard: View {
    let baby: Baby
    let isActive: Bool
    let onMakeActive: () -> Void
    let onEdit: () -> Void
    let onArchive: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            HStack(spacing: Spacing.snug) {
                Image(systemName: "birthday.cake.fill")
                    .foregroundStyle(SproutColor.primary)

                VStack(alignment: .leading, spacing: 2) {
                    Text(baby.name)
                        .font(.headline)
                    Text(SproutFormat.age(birthDate: baby.birthDate, now: Clock.millis).text)
                        .font(.callout)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if isActive {
                    Label(Str.t("baby_active"), systemImage: "checkmark.circle.fill")
                        .font(.subheadline)
                        .foregroundStyle(SproutColor.primary)
                } else {
                    Button(Str.t("baby_make_active"), action: onMakeActive)
                        .font(.subheadline)
                }
            }

            HStack {
                Spacer()
                CardAction("pencil", Str.t("cd_edit_baby", baby.name), action: onEdit)
                CardAction("archivebox", Str.t("cd_stop_tracking_baby", baby.name), action: onArchive)
                CardAction(
                    "trash",
                    Str.t("cd_delete_baby", baby.name),
                    tint: SproutColor.danger,
                    action: onDelete
                )
            }
        }
        .padding(Spacing.regular)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

private struct ArchivedRow: View {
    let baby: Baby
    let onRestore: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(baby.name).font(.subheadline.weight(.medium))
                Text(SproutFormat.age(birthDate: baby.birthDate, now: Clock.millis).text)
                    .font(.caption)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            CardAction("arrow.uturn.backward", Str.t("cd_restore_baby", baby.name), action: onRestore)
            CardAction(
                "trash",
                Str.t("cd_delete_baby", baby.name),
                tint: SproutColor.danger,
                action: onDelete
            )
        }
        .padding(Spacing.snug)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(SproutColor.background, in: RoundedRectangle(cornerRadius: Radius.control))
    }
}

/// An icon button that carries its own accessibility label, since the icon is
/// the whole of it.
private struct CardAction: View {
    let systemImage: String
    let label: String
    var tint: Color = SproutColor.onSurfaceVariant
    let action: () -> Void

    init(_ systemImage: String, _ label: String, tint: Color = SproutColor.onSurfaceVariant,
         action: @escaping () -> Void) {
        self.systemImage = systemImage
        self.label = label
        self.tint = tint
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .foregroundStyle(tint)
                .frame(width: 40, height: 36)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

private struct BabyEditor: View {
    let baby: Baby?
    let onSave: (String, Int64) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var birthDate: Int64

    init(baby: Baby?, onSave: @escaping (String, Int64) -> Void) {
        self.baby = baby
        self.onSave = onSave
        _name = State(initialValue: baby?.name ?? "")
        _birthDate = State(initialValue: baby?.birthDate ?? Clock.millis)
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField(Str.t("field_baby_name"), text: $name)
                FieldLabel(Str.t("field_date_of_birth"))
                DateField(label: Str.t("picker_born"), millis: $birthDate)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(Str.t("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(baby == nil ? Str.t("action_add") : Str.t("action_save")) {
                        onSave(name.trimmingCharacters(in: .whitespacesAndNewlines), birthDate)
                    }
                    // A baby with no name is a row nobody can tell apart on the
                    // dashboard.
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private var title: String {
        guard let baby else { return Str.t("baby_add") }
        return Str.t("baby_editor_edit", baby.name)
    }
}
