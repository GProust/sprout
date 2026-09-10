import SproutData
// `EncryptedZip` is SproutKit's — it is bytes in, bytes out, with no database in
// it, so it lives on the side of the split that has an opposite number on
// Android.
import SproutKit
import SwiftUI

/// Which file the parent asked for.
enum ReportFormat {
    case pdf
    case workbook
    /// Both of the above, inside one AES-256 archive (BDR-0013). One file rather
    /// than two, because a password is a thing you have to pass on and nobody
    /// wants to do that twice.
    case protectedZip
}

/// How long a password has to be before the button will do anything.
let minPasswordLength = 6

/// The WHO reference the growth pages are read against, as three chips.
///
/// **A view choice and nothing more**: not stored, not synced, and not remembered
/// between exports — remembering it would be keeping a baby's sex on the device
/// by the back door, which is the very field BDR-0008 decided not to have. Every
/// export starts at `.both` again.
enum ReportReference: String, CaseIterable {
    case both, girls, boys

    var sex: WhoSex? {
        switch self {
        case .both: return nil
        case .girls: return .girls
        case .boys: return .boys
        }
    }

    var label: String {
        switch self {
        case .both: return Str.t("stats_reference_both")
        case .girls: return Str.t("stats_reference_girls")
        case .boys: return Str.t("stats_reference_boys")
        }
    }
}

extension ReportPeriod {
    var label: String {
        switch self {
        case .week: return Str.t("stats_period_week")
        case .month: return Str.t("stats_period_month")
        case .quarter: return Str.t("stats_period_quarter")
        case .sinceBirth: return Str.t("report_period_birth")
        case .custom: return Str.t("report_period_custom")
        }
    }
}

@Observable
@MainActor
final class ReportViewModel {
    var babyName: String?
    var birthDate: Int64?
    var options = ReportOptions()
    var range: ReportRange?
    /// Whether the two files go out as one encrypted archive.
    var protect = false
    /// The password, for as long as this screen is open.
    ///
    /// It lives here and nowhere else: not in the options that become the report,
    /// not in preferences, not in the file name. **Sprout cannot help anyone who
    /// forgets it**, and that is the honest position — a tracker that kept a copy
    /// of the password would be a tracker with a copy of the key.
    var password = ""
    var working = false
    var failed = false
    /// The finished file, waiting for the share sheet.
    var share: URL?

    private let repository: SproutRepository
    private var babyId: Int64?

    init(repository: SproutRepository) {
        self.repository = repository
    }

    /// True when there is something to export with — a long enough password, if
    /// one is wanted.
    var canExport: Bool { !protect || password.count >= minPasswordLength }

    func setBaby(_ id: Int64) {
        guard babyId != id else { return }
        babyId = id
        guard let baby = try? repository.activeBaby(id: id) else { return }
        babyName = baby.name
        birthDate = baby.birthDate
        recomputeRange()
    }

    func setPeriod(_ period: ReportPeriod) {
        options.period = period
        // A custom range opens on the last thirty days rather than on nothing, so
        // the two pickers start somewhere sensible.
        let today = CalendarDay(millis: Clock.millis)
        if options.customFrom == nil { options.customFrom = today.adding(days: -29) }
        if options.customTo == nil { options.customTo = today }
        recomputeRange()
    }

    func setCustomFrom(_ day: CalendarDay) {
        options.customFrom = day
        recomputeRange()
    }

    func setCustomTo(_ day: CalendarDay) {
        options.customTo = day
        recomputeRange()
    }

    func setProtect(_ on: Bool) {
        protect = on
        // Turning it off forgets the password rather than keeping it warm for a
        // second try: nothing here should outlive the choice that needed it.
        if !on { password = "" }
    }

    /// The range the current choices actually mean, clamped and shown *before*
    /// anything is made — so the screen and the paper agree about what was asked
    /// for and what was possible.
    private func recomputeRange() {
        guard let birthDate else { return }
        range = reportRange(
            period: options.period,
            today: CalendarDay(millis: Clock.millis),
            birthDay: CalendarDay(millis: birthDate),
            customFrom: options.customFrom,
            customTo: options.customTo
        )
    }

    /// Builds the file and hands back something to share.
    ///
    /// All of it happens off the main actor — a year of entries is a few thousand
    /// rows to read, total and draw — and the screen says it is working meanwhile
    /// rather than appearing to have ignored the tap.
    func export(_ format: ReportFormat) async {
        guard let babyId, !working, canExport else { return }
        working = true
        failed = false

        let options = self.options
        let password = self.password
        let repository = self.repository

        do {
            let report = try await Task.detached(priority: .userInitiated) {
                try assembleReport(repository: repository, babyId: babyId, options: options)
            }.value

            let url = try await Task.detached(priority: .userInitiated) {
                try write(report: report, format: format, password: password)
            }.value

            share = url
        } catch {
            failed = true
        }
        working = false
    }
}

/// Read and totalled away from the main actor; `ReportContent` is `Sendable`, so
/// it can come back.
private func assembleReport(
    repository: SproutRepository,
    babyId: Int64,
    options: ReportOptions
) throws -> ReportContent {
    guard let baby = try repository.activeBaby(id: babyId) else {
        throw ReportFailure.noBaby
    }
    return buildReport(
        baby: baby,
        feedings: try repository.feedingsForBabyOnce(babyId),
        sleeps: try repository.sleepsForBabyOnce(babyId),
        diapers: try repository.diapersForBabyOnce(babyId),
        growth: try repository.growthForBabyOnce(babyId),
        treatments: try repository.treatmentsForBabyOnce(babyId),
        options: options,
        now: Clock.millis
    )
}

private enum ReportFailure: Error { case noBaby }

/// Renders and stages the file the parent asked for.
///
/// Deliberately not main-actor: `UIGraphicsPDFRenderer`, `UIFont`, `UIColor` and
/// `NSAttributedString` drawing are not view work, and a year of entries takes
/// long enough to draw that doing it on the main actor would freeze the screen
/// that is trying to say "working".
private func write(report: ReportContent, format: ReportFormat, password: String) throws -> URL {
    func named(_ ext: String) -> String {
        ExportFiles.fileName(
            babyName: report.babyName,
            from: report.range.from,
            to: report.range.to,
            extension: ext
        )
    }

    switch format {
    case .pdf:
        return try ExportFiles.stage(ReportPdf(report: report).data(), named: named("pdf"))

    case .workbook:
        return try ExportFiles.stage(try ReportWorkbook.bytes(report), named: named("xlsx"))

    case .protectedZip:
        // Both documents are built in full before either is encrypted: the
        // archive's headers carry each entry's size, so there is nothing to
        // stream into anyway.
        let pdf = ReportPdf(report: report).data()
        let workbook = try ReportWorkbook.bytes(report)
        let archive = try EncryptedZip.archive(
            [
                EncryptedZip.Entry(name: named("pdf"), bytes: pdf),
                EncryptedZip.Entry(name: named("xlsx"), bytes: workbook),
            ],
            password: password
        )
        return try ExportFiles.stage(archive, named: named(EncryptedZip.fileExtension))
    }
}

/// "Share a record": pick a stretch of days, get a PDF for the appointment or a
/// workbook for whoever wants the rows (BDR-0012).
///
/// **The baby is settled before this screen opens** — it is reached from the
/// share action on that baby's own page — so there is no picker here and no way
/// to export the wrong child's record by leaving a menu on the wrong name.
struct ReportScreen: View {
    let babyId: Int64

    @Environment(\.sprout) private var sprout
    @State private var model: ReportViewModel?

    var body: some View {
        Group {
            if let model {
                if model.babyName == nil {
                    EmptyHint(Str.t("stats_no_baby"))
                } else {
                    content(model)
                }
            } else {
                Color.clear
            }
        }
        .navigationTitle(Str.t("report_screen_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let model = model ?? ReportViewModel(repository: sprout.repository)
            self.model = model
            model.setBaby(babyId)
        }
    }

    @ViewBuilder
    private func content(_ model: ReportViewModel) -> some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.tight) {
                    Text(Str.t("report_intro", model.babyName ?? ""))
                        .font(.callout)
                        .foregroundStyle(SproutColor.onSurfaceVariant)

                    period(model)
                    reference(model)
                    include(model)
                    protection(model)

                    Text(Str.t("report_privacy_note"))
                        .font(.caption)
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                        .padding(.top, Spacing.regular)
                }
                .padding(Spacing.regular)
            }

            ExportBar(model: model)
        }
        .sproutStyle()
        // The share sheet, once there is a file. `item:` rather than a flag, so
        // the sheet cannot open before the URL exists.
        .sheet(item: Binding(
            get: { model.share.map(SharedFile.init) },
            set: { if $0 == nil { model.share = nil } }
        )) { file in
            ShareSheet(url: file.url)
        }
    }

    @ViewBuilder
    private func period(_ model: ReportViewModel) -> some View {
        SectionLabel(Str.t("report_period_label"))
        ChoiceChips(
            options: ReportPeriod.allCases,
            selection: Binding(
                get: { model.options.period },
                set: { model.setPeriod($0 ?? model.options.period) }
            ),
            label: \.label
        )

        if model.options.period == .custom {
            VStack(spacing: Spacing.tight) {
                DateField(
                    label: Str.t("report_custom_from"),
                    millis: Binding(
                        get: { (model.options.customFrom ?? CalendarDay(millis: Clock.millis)).startMillis() },
                        set: { model.setCustomFrom(CalendarDay(millis: $0)) }
                    )
                )
                DateField(
                    label: Str.t("report_custom_to"),
                    millis: Binding(
                        get: { (model.options.customTo ?? CalendarDay(millis: Clock.millis)).startMillis() },
                        set: { model.setCustomTo(CalendarDay(millis: $0)) }
                    )
                )
            }
            .padding(.top, Spacing.tight)
        }

        // What the choices actually mean, shown before anything is made: a range
        // clamped to the birth says so here rather than surprising the reader on
        // the paper.
        if let range = model.range {
            Text(
                Str.t(
                    "stats_window_range",
                    SproutDateStyle.date(range.from.startMillis()),
                    SproutDateStyle.date(range.to.startMillis())
                ) + " · " + Str.t("report_days_count", range.dayCount)
            )
            .font(.caption)
            .foregroundStyle(SproutColor.onSurfaceVariant)
            .padding(.top, Spacing.hairline)
        }
    }

    @ViewBuilder
    private func reference(_ model: ReportViewModel) -> some View {
        SectionLabel(Str.t("report_reference_label"))
        ChoiceChips(
            options: ReportReference.allCases,
            selection: Binding(
                get: { ReportReference.allCases.first { $0.sex == model.options.reference } ?? .both },
                set: { model.options.reference = ($0 ?? .both).sex }
            ),
            label: \.label
        )
        Text(Str.t("report_reference_hint"))
            .font(.caption)
            .foregroundStyle(SproutColor.onSurfaceVariant)
    }

    @ViewBuilder
    private func include(_ model: ReportViewModel) -> some View {
        SectionLabel(Str.t("report_include_label"))
        ToggleRow(
            title: Str.t("report_include_daily"),
            subtitle: Str.t("report_include_daily_hint"),
            isOn: Binding(
                get: { model.options.includeDailyTable },
                set: { model.options.includeDailyTable = $0 }
            )
        )
        ToggleRow(
            title: Str.t("screen_treatments"),
            subtitle: Str.t("report_include_treatments_hint"),
            isOn: Binding(
                get: { model.options.includeTreatments },
                set: { model.options.includeTreatments = $0 }
            )
        )
        ToggleRow(
            title: Str.t("report_include_notes"),
            subtitle: Str.t("report_include_notes_hint"),
            isOn: Binding(
                get: { model.options.includeNotes },
                set: { model.options.includeNotes = $0 }
            )
        )
    }

    @ViewBuilder
    private func protection(_ model: ReportViewModel) -> some View {
        SectionLabel(Str.t("report_protect_label"))
        ToggleRow(
            title: Str.t("report_protect"),
            subtitle: Str.t("report_protect_hint"),
            isOn: Binding(get: { model.protect }, set: { model.setProtect($0) })
        )

        if model.protect {
            PasswordField(password: Binding(get: { model.password }, set: { model.password = $0 }))

            Text(Str.t("report_password_help"))
                .font(.caption)
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .padding(.top, Spacing.tight)
            // The cost of the sound choice, said plainly: AES-256 is what a
            // health record deserves, and it is not what Explorer opens by
            // double-click (BDR-0013).
            Text(Str.t("report_protect_compat"))
                .font(.caption)
                .foregroundStyle(SproutColor.onSurfaceVariant)
                .padding(.top, Spacing.tight)
        }
    }
}

/// What this screen exists to produce, always within reach.
///
/// A bar of its own rather than the foot of the list: everything above it is a
/// choice with a sensible default, so the common trip through here is "open it,
/// tap PDF" — and a screen whose whole point is one tap should not open with that
/// tap below the fold, let alone a scroll and a half down once a custom range has
/// added its two date fields.
private struct ExportBar: View {
    let model: ReportViewModel

    var body: some View {
        VStack(spacing: Spacing.snug) {
            if model.working {
                HStack(spacing: Spacing.tight) {
                    ProgressView().controlSize(.small)
                    Text(Str.t("report_working")).font(.callout)
                }
            }
            if model.failed {
                Text(Str.t("report_failed"))
                    .font(.callout)
                    .foregroundStyle(SproutColor.danger)
            }

            if model.protect {
                // Protected, the two documents travel as one archive under one
                // password, so there is one button rather than two.
                Button {
                    Task { await model.export(.protectedZip) }
                } label: {
                    Label(Str.t("report_make_protected"), systemImage: "lock.fill")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Spacing.tight)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!model.canExport || model.working)
            } else {
                HStack(spacing: Spacing.snug) {
                    Button {
                        Task { await model.export(.pdf) }
                    } label: {
                        Label(Str.t("report_make_pdf"), systemImage: "doc.text")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, Spacing.tight)
                    }
                    .buttonStyle(.borderedProminent)

                    Button {
                        Task { await model.export(.workbook) }
                    } label: {
                        Label(Str.t("report_make_workbook"), systemImage: "tablecells")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, Spacing.tight)
                    }
                    .buttonStyle(.bordered)
                }
                .disabled(model.working)
            }
        }
        .padding(Spacing.regular)
        .background(SproutColor.surface)
    }
}

/// The password, typed by the parent and kept by nobody.
///
/// **It can be shown**, because a password nobody can read is a password typed
/// wrongly and then given out wrongly — and this one has to be repeated to
/// another person to be any use at all.
private struct PasswordField: View {
    @Binding var password: String
    @State private var visible = false

    private var tooShort: Bool { !password.isEmpty && password.count < minPasswordLength }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.hairline) {
            HStack {
                Group {
                    if visible {
                        TextField(Str.t("report_password"), text: $password)
                    } else {
                        SecureField(Str.t("report_password"), text: $password)
                    }
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

                Button { visible.toggle() } label: {
                    Image(systemName: visible ? "eye.slash" : "eye")
                        .foregroundStyle(SproutColor.onSurfaceVariant)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Str.t(visible ? "cd_hide_password" : "cd_show_password"))
            }
            .padding(Spacing.snug)
            .background(SproutColor.surface, in: RoundedRectangle(cornerRadius: Radius.control))
            .overlay(
                RoundedRectangle(cornerRadius: Radius.control)
                    .stroke(tooShort ? SproutColor.danger : .clear, lineWidth: 1)
            )

            if tooShort {
                Text(Str.t("report_password_short", minPasswordLength))
                    .font(.caption)
                    .foregroundStyle(SproutColor.danger)
            }
        }
        .padding(.top, Spacing.tight)
    }
}

private struct ToggleRow: View {
    let title: String
    let subtitle: String
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.callout)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(SproutColor.onSurfaceVariant)
            }
        }
        .padding(.vertical, Spacing.hairline)
    }
}

