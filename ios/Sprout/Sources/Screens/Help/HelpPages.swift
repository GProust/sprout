import Foundation

// What each page's help sheet says (BDR-26), and the one list both apps show.
// From `ui/help/HelpPages.kt`.
//
// The two are written in the same order: the section headings, then the pages'
// titles, then each page's entries. `ios/tools/check_string_keys.py` reads this
// file against Android's, and Android's `HelpPagesTest` reads Android's against
// this one, so a page cannot say more on one phone than on the other. Keep the
// string keys in this file to the list itself — both checks read every key it
// names, in order. The keys are literal, so the same check also sees that each
// one is in the catalog.

/// The parts a help sheet can have, in the order they are read.
///
/// Their headings are shared by every page, so "Good to know" is one string in
/// seven languages rather than seventeen.
enum HelpSection: CaseIterable {
    case purpose
    case how
    case goodToKnow

    var title: String {
        switch self {
        case .purpose: return Str.t("help_section_purpose")
        case .how: return Str.t("help_section_how")
        case .goodToKnow: return Str.t("help_section_good_to_know")
        }
    }
}

/// Every page that has a **?**.
///
/// The raw value names the page in its strings (`help_<page>_<section>`) and in
/// the button's accessibility identifier (`help-<page>`), which is how a UI test
/// finds it in any language.
enum HelpPage: String, CaseIterable {
    case home
    case baby
    case trends
    case you
    case feeding
    case pumping
    case sleep
    case diaper
    case growth
    case treatments
    case medicines
    case foods
    case wellbeing
    case babies
    case sharing
    case report
    case settings

    /// The page's own name rather than a string of its own: the sheet is about
    /// the screen behind it, and calls it what the screen calls itself.
    var title: String {
        switch self {
        case .home: return Str.t("nav_home")
        case .baby: return Str.t("nav_baby")
        case .trends: return Str.t("screen_stats")
        case .you: return Str.t("nav_you")
        case .feeding: return Str.t("screen_feeding")
        case .pumping: return Str.t("screen_pumping")
        case .sleep: return Str.t("screen_sleep")
        case .diaper: return Str.t("screen_diapers")
        case .growth: return Str.t("screen_growth")
        case .treatments: return Str.t("screen_treatments")
        case .medicines: return Str.t("screen_medicines")
        case .foods: return Str.t("screen_foods")
        case .wellbeing: return Str.t("screen_wellbeing")
        case .babies: return Str.t("screen_babies")
        case .sharing: return Str.t("screen_sync")
        case .report: return Str.t("report_screen_title")
        case .settings: return Str.t("screen_settings")
        }
    }
}

/// One part of one page's sheet: the shared heading, and this page's words under it.
struct HelpEntry {
    let section: HelpSection
    let body: String
}

/// What a page's sheet says, section by section.
///
/// Every page has all three today. One may leave out a section it has nothing
/// for, as long as Android's list leaves out the same one — which is what the
/// two checks compare.
func helpEntries(_ page: HelpPage) -> [HelpEntry] {
    switch page {
    case .home:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_home_purpose")),
            HelpEntry(section: .how, body: Str.t("help_home_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_home_good_to_know")),
        ]
    case .baby:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_baby_purpose")),
            HelpEntry(section: .how, body: Str.t("help_baby_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_baby_good_to_know")),
        ]
    case .trends:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_trends_purpose")),
            HelpEntry(section: .how, body: Str.t("help_trends_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_trends_good_to_know")),
        ]
    case .you:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_you_purpose")),
            HelpEntry(section: .how, body: Str.t("help_you_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_you_good_to_know")),
        ]
    case .feeding:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_feeding_purpose")),
            HelpEntry(section: .how, body: Str.t("help_feeding_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_feeding_good_to_know")),
        ]
    case .pumping:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_pumping_purpose")),
            HelpEntry(section: .how, body: Str.t("help_pumping_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_pumping_good_to_know")),
        ]
    case .sleep:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_sleep_purpose")),
            HelpEntry(section: .how, body: Str.t("help_sleep_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_sleep_good_to_know")),
        ]
    case .diaper:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_diaper_purpose")),
            HelpEntry(section: .how, body: Str.t("help_diaper_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_diaper_good_to_know")),
        ]
    case .growth:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_growth_purpose")),
            HelpEntry(section: .how, body: Str.t("help_growth_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_growth_good_to_know")),
        ]
    case .treatments:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_treatments_purpose")),
            HelpEntry(section: .how, body: Str.t("help_treatments_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_treatments_good_to_know")),
        ]
    case .medicines:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_medicines_purpose")),
            HelpEntry(section: .how, body: Str.t("help_medicines_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_medicines_good_to_know")),
        ]
    case .foods:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_foods_purpose")),
            HelpEntry(section: .how, body: Str.t("help_foods_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_foods_good_to_know")),
        ]
    case .wellbeing:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_wellbeing_purpose")),
            HelpEntry(section: .how, body: Str.t("help_wellbeing_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_wellbeing_good_to_know")),
        ]
    case .babies:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_babies_purpose")),
            HelpEntry(section: .how, body: Str.t("help_babies_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_babies_good_to_know")),
        ]
    case .sharing:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_sharing_purpose")),
            HelpEntry(section: .how, body: Str.t("help_sharing_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_sharing_good_to_know")),
        ]
    case .report:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_report_purpose")),
            HelpEntry(section: .how, body: Str.t("help_report_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_report_good_to_know")),
        ]
    case .settings:
        return [
            HelpEntry(section: .purpose, body: Str.t("help_settings_purpose")),
            HelpEntry(section: .how, body: Str.t("help_settings_how")),
            HelpEntry(section: .goodToKnow, body: Str.t("help_settings_good_to_know")),
        ]
    }
}
