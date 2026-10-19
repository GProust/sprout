import SproutData
import UIKit

/// The allergy list on its own (BDR-21), from `AllergyListPdf.kt`: whose it is,
/// and each allergy with what the parents noted about it — the page a nursery
/// pins up or a babysitter is handed, without the rest of the record.
///
/// Drawn the way ``ReportPdf`` draws, onto the context `UIGraphicsPDFRenderer`
/// hands out (ADR-0013), on the same paper and in the same ink. It says what was
/// noted and nothing more: no instructions, no symptoms to watch for, no "in case
/// of emergency". That is the doctor's to write, and a page that sounded like
/// one would be photocopied as one.
struct AllergyListPdf {
    let list: AllergyList

    private let paper = ReportPdf.Paper.forLocale(.current)
    private let margin: CGFloat = 42
    private var contentWidth: CGFloat { paper.width - margin * 2 }

    private let ink = UIColor(red: 0.08, green: 0.10, blue: 0.09, alpha: 1)
    private let muted = UIColor(red: 0.42, green: 0.46, blue: 0.43, alpha: 1)
    private let rule = UIColor(red: 0.73, green: 0.76, blue: 0.73, alpha: 1)

    init(list: AllergyList) {
        self.list = list
    }

    /// The whole list as PDF bytes.
    func data() -> Data {
        let bounds = CGRect(x: 0, y: 0, width: paper.width, height: paper.height)
        let format = UIGraphicsPDFRendererFormat()
        format.documentInfo = [
            kCGPDFContextTitle as String: Str.t("allergies_title") + " — " + list.babyName,
            kCGPDFContextCreator as String: Str.t("app_name"),
        ]
        let renderer = UIGraphicsPDFRenderer(bounds: bounds, format: format)

        return renderer.pdfData { context in
            context.beginPage()
            var y = margin

            func text(_ value: String, font: UIFont, colour: UIColor, gapAfter: CGFloat = 4) {
                let string = NSAttributedString(string: value, attributes: [
                    .font: font,
                    .foregroundColor: colour,
                ])
                let height = ceil(string.boundingRect(
                    with: CGSize(width: contentWidth, height: .greatestFiniteMagnitude),
                    options: [.usesLineFragmentOrigin, .usesFontLeading],
                    context: nil
                ).height)
                if y + height > paper.height - margin {
                    context.beginPage()
                    y = margin
                }
                string.draw(
                    with: CGRect(x: margin, y: y, width: contentWidth, height: height),
                    options: [.usesLineFragmentOrigin, .usesFontLeading],
                    context: nil
                )
                y += height + gapAfter
            }

            func drawRule(at lineY: CGFloat) {
                guard let cg = UIGraphicsGetCurrentContext() else { return }
                cg.setStrokeColor(rule.cgColor)
                cg.setLineWidth(0.6)
                cg.move(to: CGPoint(x: margin, y: lineY))
                cg.addLine(to: CGPoint(x: paper.width - margin, y: lineY))
                cg.strokePath()
            }

            text(Str.t("allergies_title"), font: serif(21), colour: ink, gapAfter: 6)
            text(list.babyName, font: .systemFont(ofSize: 12.5, weight: .semibold), colour: ink, gapAfter: 2)
            text(
                Str.t(
                    "allergy_list_identity",
                    SproutDateStyle.date(list.birthDate),
                    SproutFormat.age(birthDate: list.birthDate, now: list.generatedAt).text,
                    SproutDateStyle.date(list.generatedAt)
                ),
                font: .systemFont(ofSize: 9),
                colour: muted,
                gapAfter: 14
            )
            drawRule(at: y)
            y += 12

            if list.allergies.isEmpty {
                text(Str.t("allergies_none"), font: .systemFont(ofSize: 9.6), colour: ink, gapAfter: 12)
            }
            for allergy in list.allergies {
                text(allergy.name, font: .systemFont(ofSize: 12.5, weight: .semibold), colour: ink, gapAfter: 2)
                var status = [Str.t(allergy.confirmed ? "allergy_confirmed_short" : "allergy_noted_short")]
                if let noticed = allergy.noticedAt {
                    status.append(Str.t("allergy_noticed_line", SproutDateStyle.date(noticed)))
                }
                text(
                    status.joined(separator: Str.t("feeding_detail_separator")),
                    font: .systemFont(ofSize: 9),
                    colour: muted,
                    gapAfter: allergy.notes == nil ? 12 : 3
                )
                if let notes = allergy.notes {
                    text(notes, font: .systemFont(ofSize: 9.6), colour: ink, gapAfter: 12)
                }
            }

            drawRule(at: y)
            y += 8
            text(Str.t("allergy_list_note"), font: .systemFont(ofSize: 7.4), colour: muted, gapAfter: 0)
        }
    }

    private func serif(_ size: CGFloat) -> UIFont {
        let base = UIFont.systemFont(ofSize: size, weight: .bold)
        let descriptor = base.fontDescriptor.withDesign(.serif) ?? base.fontDescriptor
        return UIFont(descriptor: descriptor, size: size)
    }
}
