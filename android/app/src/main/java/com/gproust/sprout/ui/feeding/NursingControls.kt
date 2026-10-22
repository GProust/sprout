package com.gproust.sprout.ui.feeding

import com.gproust.sprout.data.local.BreastSide

/**
 * What a running feed can be asked to do — from the timer screen, and from the
 * notification or the Live Activity that shows it outside the app (BDR-24).
 *
 * One list for every place that offers them, so a tap outside the app is the
 * same act as the same tap on the timer: the same function changes the same
 * session, and *Stop & save* saves the same feed. The same cases are tested on
 * iOS (`NursingControls.swift`).
 */
enum class NursingControl { PAUSE, SWITCH, RESUME_LEFT, RESUME_RIGHT, STOP }

/**
 * The controls a feed offers outside the app, in the order they are shown:
 * three at most, which is what a notification has room for.
 *
 * Coming back from a break names the breast (BDR-17), so a feed on a break
 * offers *Resume left* and *Resume right* rather than a single *Resume*.
 */
fun nursingControls(session: NursingSession): List<NursingControl> =
    if (session.isPaused) {
        listOf(NursingControl.RESUME_LEFT, NursingControl.RESUME_RIGHT, NursingControl.STOP)
    } else {
        listOf(NursingControl.PAUSE, NursingControl.SWITCH, NursingControl.STOP)
    }

/**
 * This feed after [control], tapped at [now].
 *
 * A control that does not fit the feed's state changes nothing: a notification
 * can be a tap behind the phone it is on, and a *Switch* left on screen while
 * the feed went on a break must not quietly resume it. Pausing twice is already
 * nothing ([NursingSession.paused]). [NursingControl.STOP] leaves the feed as it
 * is — ending it is the store's to do, so that only one tap is ever handed it.
 */
fun NursingSession.after(control: NursingControl, now: Long): NursingSession = when (control) {
    NursingControl.PAUSE -> paused(now)
    NursingControl.SWITCH -> if (isPaused) this else switched(now)
    NursingControl.RESUME_LEFT -> if (isPaused) resumed(BreastSide.LEFT, now) else this
    NursingControl.RESUME_RIGHT -> if (isPaused) resumed(BreastSide.RIGHT, now) else this
    NursingControl.STOP -> this
}
