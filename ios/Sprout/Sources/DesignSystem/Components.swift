import SproutData
import SwiftUI

// The shared views, from `ui/common/Components.kt`.
//
// Everything a tracking screen is built out of lives here, so the screens stay
// about *what* they log rather than how a card is spaced. Where SwiftUI has a
// better idiom than Compose the idiom wins — a sheet is a `.sheet`, a picker is
// a `DatePicker` — but the shape of each piece and the reason it exists are the
// same on both phones.

// MARK: - Structure

/// A section heading inside a screen.
struct SectionLabel: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.headline)
            .foregroundStyle(SproutColor.onSurface)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The label above a form field.
struct FieldLabel: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(SproutColor.onSurfaceVariant)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, Spacing.tight)
    }
}

/// What a history list shows before anything has been logged.
///
/// A sentence, not an illustration: the first thing a parent sees on this screen
/// is at 3 a.m. and should tell them what to do, not decorate the wait.
struct EmptyHint: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.callout)
            .foregroundStyle(SproutColor.onSurfaceVariant)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(Spacing.section)
    }
}

/// A day divider in a history list: "Today", "Yesterday", or the date.
struct DayHeader: View {
    let dayStartMillis: Int64
    let now: Int64

    var body: some View {
        Text(SproutFormat.dayLabel(dayStartMillis, now: now).text)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(SproutColor.onSurfaceVariant)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, Spacing.hairline)
    }
}

/// One figure on the dashboard — today's feeds, the sleep total, the nappy count.
struct StatCard: View {
    let label: String
    let value: String
    let systemImage: String

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.tight) {
            Image(systemName: systemImage)
                .font(.title3)
                .foregroundStyle(SproutColor.primary)
            Text(value)
                .font(.title2.weight(.semibold))
                .foregroundStyle(SproutColor.onSurface)
                // A long duration must shrink rather than wrap: the row of cards
                // has to stay one row at every text size.
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(.caption)
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.regular)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
    }
}

/// One logged entry in a history list.
///
/// `details` adds an expandable breakdown under the row; `action` puts a one-tap
/// shortcut beside it, for the state change that would otherwise mean opening
/// the editor.
struct EntryCard<Details: View, Action: View>: View {
    let title: String
    var subtitle: String = ""
    let meta: String
    let systemImage: String
    var onTap: (() -> Void)?
    let onDelete: () -> Void
    @ViewBuilder var details: () -> Details
    @ViewBuilder var action: () -> Action

    @State private var expanded = false

    private var hasDetails: Bool { Details.self != EmptyView.self }
    private var hasAction: Bool { Action.self != EmptyView.self }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            row
            if hasDetails || hasAction {
                HStack(spacing: Spacing.tight) {
                    if hasDetails {
                        Button {
                            withAnimation(.snappy) { expanded.toggle() }
                        } label: {
                            HStack(spacing: Spacing.hairline) {
                                Text(Str.t("action_details"))
                                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            }
                            .font(.footnote)
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(SproutColor.primary)
                    }
                    action()
                }
                .padding(.horizontal, Spacing.regular)
                .padding(.bottom, Spacing.snug)

                if hasDetails, expanded {
                    details()
                        .padding(.horizontal, Spacing.regular)
                        .padding(.bottom, Spacing.snug)
                }
            }
        }
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.card))
        .contentShape(Rectangle())
        .onTapGesture { onTap?() }
    }

    private var row: some View {
        HStack(spacing: Spacing.snug) {
            Image(systemName: systemImage)
                .font(.title3)
                .foregroundStyle(SproutColor.primary)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(SproutColor.onSurface)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.callout)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(meta)
                .font(.caption)
                .foregroundStyle(SproutColor.onSurfaceVariant)

            Button(action: onDelete) {
                Image(systemName: "trash")
                    .foregroundStyle(SproutColor.outline)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Str.t("cd_delete"))
        }
        .padding(.leading, Spacing.regular)
        .padding(.trailing, Spacing.snug)
        .padding(.vertical, Spacing.snug)
    }
}

extension EntryCard where Details == EmptyView, Action == EmptyView {
    init(
        title: String,
        subtitle: String = "",
        meta: String,
        systemImage: String,
        onTap: (() -> Void)? = nil,
        onDelete: @escaping () -> Void
    ) {
        self.init(
            title: title,
            subtitle: subtitle,
            meta: meta,
            systemImage: systemImage,
            onTap: onTap,
            onDelete: onDelete,
            details: { EmptyView() },
            action: { EmptyView() }
        )
    }
}

// MARK: - Input

/// A row of single-choice chips.
///
/// Chips rather than a `Picker`: the options are the point of the form — which
/// breast, which nappy, where the baby slept — and a wheel or a menu hides them
/// behind a tap that a one-handed parent has to aim at.
struct ChoiceChips<Option: Hashable>: View {
    let options: [Option]
    @Binding var selection: Option?
    let label: (Option) -> String
    /// Whether tapping the selected chip clears it. On for the optional fields,
    /// where "not recorded" is a real answer and not a gap (BDR-0014).
    var allowsDeselection = false

    var body: some View {
        FlowLayout(spacing: Spacing.tight) {
            ForEach(options, id: \.self) { option in
                let isSelected = option == selection
                Button {
                    selection = (isSelected && allowsDeselection) ? nil : option
                } label: {
                    Text(label(option))
                        .font(.callout)
                        .padding(.horizontal, Spacing.snug)
                        .padding(.vertical, Spacing.tight)
                        .background(
                            isSelected ? SproutColor.primaryContainer : SproutColor.surface,
                            in: Capsule()
                        )
                        .overlay(
                            Capsule().stroke(
                                isSelected ? SproutColor.primary : SproutColor.outline.opacity(0.4),
                                lineWidth: 1
                            )
                        )
                        .foregroundStyle(
                            isSelected ? SproutColor.onPrimaryContainer : SproutColor.onSurface
                        )
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? [.isSelected] : [])
            }
        }
    }
}

/// Digits only, with an optional unit after the field.
struct NumberField: View {
    let label: String
    @Binding var text: String
    var suffix: String?

    var body: some View {
        HStack {
            TextField(label, text: $text)
                .keyboardType(.numberPad)
                .onChange(of: text) { _, new in
                    // Filtered rather than validated on submit: a stray letter
                    // from a predictive keyboard should never reach the model.
                    let digits = new.filter(\.isNumber)
                    if digits != new { text = digits }
                }
            if let suffix {
                Text(suffix).foregroundStyle(SproutColor.onSurfaceVariant)
            }
        }
        .padding(Spacing.snug)
        .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.control))
    }
}

struct NotesField: View {
    @Binding var text: String

    var body: some View {
        TextField(Str.t("field_notes_optional"), text: $text, axis: .vertical)
            .lineLimit(1...4)
            .padding(Spacing.snug)
            .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.control))
    }
}

/// Date and time on one row, which is how an entry is nearly always corrected:
/// the parent is logging a feed that happened twenty minutes ago.
struct DateTimeField: View {
    let label: String
    @Binding var millis: Int64

    private var date: Binding<Date> {
        Binding(
            get: { SproutFormat.date(from: millis) },
            set: { millis = SproutFormat.millis(from: $0) }
        )
    }

    var body: some View {
        DatePicker(label, selection: date, displayedComponents: [.date, .hourAndMinute])
            .font(.callout)
    }
}

// MARK: - Actions

/// The "+" that opens a tracking screen's log form.
struct AddEntryButton: View {
    let accessibilityLabel: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "plus")
                .font(.title2.weight(.semibold))
                .foregroundStyle(SproutColor.onPrimary)
                .frame(width: 56, height: 56)
                .background(SproutColor.primary, in: Circle())
                .shadow(color: .black.opacity(0.15), radius: 8, y: 4)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }
}

/// The confirmation before a destructive tap.
///
/// Entries go for good, and the delete button sits within a thumb's width of the
/// rest of a card — a mis-tap while holding a baby should not cost the log.
extension View {
    func confirmDelete(
        isPresented: Binding<Bool>,
        title: String = Str.t("entry_delete_title"),
        message: String = Str.t("entry_delete_body"),
        onConfirm: @escaping () -> Void
    ) -> some View {
        confirmationDialog(title, isPresented: isPresented, titleVisibility: .visible) {
            Button(Str.t("action_delete"), role: .destructive, action: onConfirm)
            Button(Str.t("action_cancel"), role: .cancel) {}
        } message: {
            Text(message)
        }
    }
}

// MARK: - Layout

/// Chips wrap onto as many rows as they need.
///
/// SwiftUI has no `FlowRow`, and seven languages means a row of chips that fits
/// in English does not fit in German. Twenty lines of `Layout` rather than a
/// horizontal scroll view, because a chip a parent cannot see is a chip they
/// will not tap.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        let rows = arrange(subviews: subviews, width: width)
        let height = rows.reduce(CGFloat.zero) { $0 + $1.height + spacing }
        return CGSize(width: width == .infinity ? rows.map(\.width).max() ?? 0 : width,
                      height: max(0, height - spacing))
    }

    func placeSubviews(
        in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()
    ) {
        var y = bounds.minY
        for row in arrange(subviews: subviews, width: bounds.width) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(
                    at: CGPoint(x: x, y: y + (row.height - size.height) / 2),
                    proposal: ProposedViewSize(size)
                )
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(subviews: Subviews, width: CGFloat) -> [Row] {
        var rows: [Row] = []
        var current = Row()

        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let needed = current.indices.isEmpty ? size.width : current.width + spacing + size.width
            if needed > width, !current.indices.isEmpty {
                rows.append(current)
                current = Row()
            }
            current.width = current.indices.isEmpty ? size.width : current.width + spacing + size.width
            current.height = max(current.height, size.height)
            current.indices.append(index)
        }
        if !current.indices.isEmpty { rows.append(current) }
        return rows
    }
}
