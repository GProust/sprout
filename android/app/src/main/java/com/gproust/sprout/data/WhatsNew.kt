package com.gproust.sprout.data

/**
 * Every "what's new" announcement this app carries, oldest first (BDR-22).
 *
 * An announcement is a highlight, not a changelog line: one for a release that
 * brings something a parent would want to know is there, none for a release of
 * fixes. The ids are one sequence shared with iOS (`announcementIds` in
 * `SproutData/WhatsNew.swift`), so the same id is the same text on both phones.
 * Append, never renumber or reuse one: a phone remembers the newest id it has
 * shown, and a reused number would read as already seen.
 */
val ANNOUNCEMENT_IDS: List<Int> = listOf(1, 2)

/**
 * Which announcements the dashboard's card offers, newest first.
 *
 * [lastSeen] is the newest id this phone has already shown or put away, and
 * null when it has never stored one. That happens in two cases, and only one of
 * them reaches the dashboard:
 * - A fresh install marks every announcement seen when onboarding finishes, so
 *   it never gets here: everything is new to someone who has just arrived, and
 *   nothing has *changed*.
 * - A phone updating from a version before announcements existed has a profile
 *   and nothing stored. It is offered the newest announcement only, not the
 *   whole history.
 */
fun announcementsToShow(available: List<Int>, lastSeen: Int?): List<Int> {
    val newest = available.maxOrNull() ?: return emptyList()
    if (lastSeen == null) return listOf(newest)
    return available.filter { it > lastSeen }.sortedDescending()
}
