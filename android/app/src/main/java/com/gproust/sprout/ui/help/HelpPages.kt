package com.gproust.sprout.ui.help

import androidx.annotation.StringRes
import com.gproust.sprout.R

// What each page's help sheet says (BDR-26), and the one list both apps show.
//
// iOS holds the same list in `Screens/Help/HelpPages.swift`, written in the same
// order: the section headings, then the pages' titles, then each page's
// entries. `HelpPagesTest` reads that file against this one, and
// `ios/tools/check_string_keys.py` reads this one against that, so a page
// cannot say more on one phone than on the other. Keep the string keys in this
// file to the list itself — both checks read every key it names, in order.

/**
 * The parts a help sheet can have, in the order they are read.
 *
 * Their headings are shared by every page, so "Good to know" is one string in
 * seven languages rather than seventeen. [key] is the last part of each body's
 * resource name, `help_<page>_<key>`, which `HelpPagesTest` holds every entry to.
 */
enum class HelpSection(@param:StringRes val title: Int, val key: String) {
    PURPOSE(R.string.help_section_purpose, "purpose"),
    HOW(R.string.help_section_how, "how"),
    GOOD_TO_KNOW(R.string.help_section_good_to_know, "good_to_know"),
}

/**
 * Every page that has a **?**, with the title its sheet carries.
 *
 * [key] names the page in its strings (`help_<key>_<section>`) and, on iOS, in
 * the button's accessibility identifier (`help-<key>`). The titles are the
 * pages' own names rather than strings of their own: the sheet is about the
 * screen behind it, and should call it what the screen calls itself.
 */
enum class HelpPage(val key: String, @param:StringRes val title: Int) {
    HOME("home", R.string.nav_home),
    BABY("baby", R.string.nav_baby),
    TRENDS("trends", R.string.screen_stats),
    YOU("you", R.string.nav_you),
    FEEDING("feeding", R.string.screen_feeding),
    PUMPING("pumping", R.string.screen_pumping),
    SLEEP("sleep", R.string.screen_sleep),
    DIAPER("diaper", R.string.screen_diapers),
    GROWTH("growth", R.string.screen_growth),
    TREATMENTS("treatments", R.string.screen_treatments),
    MEDICINES("medicines", R.string.screen_medicines),
    FOODS("foods", R.string.screen_foods),
    WELLBEING("wellbeing", R.string.screen_wellbeing),
    BABIES("babies", R.string.screen_babies),
    SHARING("sharing", R.string.screen_sync),
    REPORT("report", R.string.report_screen_title),
    SETTINGS("settings", R.string.screen_settings),
}

/** One part of one page's sheet: the shared heading, and this page's words under it. */
data class HelpEntry(val section: HelpSection, @param:StringRes val body: Int)

/**
 * What a page's sheet says, section by section.
 *
 * Every page has all three today. One may leave out a section it has nothing
 * for, as long as the Swift list leaves out the same one — which is what the two
 * checks compare.
 */
fun helpEntries(page: HelpPage): List<HelpEntry> = when (page) {
    HelpPage.HOME -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_home_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_home_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_home_good_to_know),
    )
    HelpPage.BABY -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_baby_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_baby_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_baby_good_to_know),
    )
    HelpPage.TRENDS -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_trends_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_trends_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_trends_good_to_know),
    )
    HelpPage.YOU -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_you_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_you_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_you_good_to_know),
    )
    HelpPage.FEEDING -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_feeding_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_feeding_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_feeding_good_to_know),
    )
    HelpPage.PUMPING -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_pumping_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_pumping_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_pumping_good_to_know),
    )
    HelpPage.SLEEP -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_sleep_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_sleep_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_sleep_good_to_know),
    )
    HelpPage.DIAPER -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_diaper_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_diaper_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_diaper_good_to_know),
    )
    HelpPage.GROWTH -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_growth_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_growth_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_growth_good_to_know),
    )
    HelpPage.TREATMENTS -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_treatments_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_treatments_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_treatments_good_to_know),
    )
    HelpPage.MEDICINES -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_medicines_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_medicines_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_medicines_good_to_know),
    )
    HelpPage.FOODS -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_foods_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_foods_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_foods_good_to_know),
    )
    HelpPage.WELLBEING -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_wellbeing_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_wellbeing_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_wellbeing_good_to_know),
    )
    HelpPage.BABIES -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_babies_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_babies_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_babies_good_to_know),
    )
    HelpPage.SHARING -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_sharing_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_sharing_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_sharing_good_to_know),
    )
    HelpPage.REPORT -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_report_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_report_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_report_good_to_know),
    )
    HelpPage.SETTINGS -> listOf(
        HelpEntry(HelpSection.PURPOSE, R.string.help_settings_purpose),
        HelpEntry(HelpSection.HOW, R.string.help_settings_how),
        HelpEntry(HelpSection.GOOD_TO_KNOW, R.string.help_settings_good_to_know),
    )
}
