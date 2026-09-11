package com.gproust.sprout.data.sync.nearby

/**
 * When Sprout is allowed to use the radio, and for how long (ADR-0010).
 *
 * The decision this encodes is that **discovery is bounded and triggered, never
 * continuous**: a short window when the app comes to the foreground, or when the
 * user asks. Nothing runs in the background, nothing polls, and nothing keeps a
 * radio warm. A feeding log does not need second-by-second freshness, and a
 * phone that spends its battery looking for a partner who is asleep is a worse
 * product than one that syncs a few minutes later.
 *
 * It lives apart from the Bluetooth plumbing on purpose: this is the part with
 * an opinion in it, and the part worth testing.
 */
object NearbyPolicy {

    /**
     * How long a window stays open.
     *
     * Long enough for a BLE advertisement to be seen — they go out every few
     * hundred milliseconds — and for a connection to carry a few hundred
     * kilobytes. Short enough that a launch costs seconds of radio, not minutes.
     */
    const val WINDOW_MS: Long = 10_000

    /**
     * The shortest gap between two windows opened by simply *using* the app.
     *
     * Sprout gets opened many times a day, often for fifteen seconds at 3 a.m.
     * Without this, every one of those would light up the radio.
     */
    const val MIN_INTERVAL_MS: Long = 5 * 60 * 1000

    /**
     * Whether opening a window now is allowed, given when the last one opened.
     *
     * @param lastOpenedAt null when no window has ever opened on this phone.
     * @param userAsked true when the parent tapped *Sync now* — an explicit
     * request is never throttled, because a user who asks and is silently
     * refused has no way to tell that from a failure.
     */
    fun mayOpenWindow(lastOpenedAt: Long?, now: Long, userAsked: Boolean = false): Boolean {
        if (userAsked) return true
        if (lastOpenedAt == null) return true
        // A clock that moved backwards (time zone, manual change, reboot) must
        // not lock discovery out until it catches up.
        if (now < lastOpenedAt) return true
        return now - lastOpenedAt >= MIN_INTERVAL_MS
    }

    /** When a window opened at [openedAt] should stop. */
    fun windowEndsAt(openedAt: Long): Long = openedAt + WINDOW_MS
}
