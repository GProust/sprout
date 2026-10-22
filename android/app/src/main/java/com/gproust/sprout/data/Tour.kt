package com.gproust.sprout.data

/**
 * The guided tour's steps, in the order they are shown (BDR-27): each a short
 * card pointing at one area of the dashboard.
 *
 * The same steps, in the same order and with the same words, as `TourStep` in
 * `SproutData/Tour.swift`.
 */
enum class TourStep {
    /** The log tiles: feed, sleep, nappy and the rest. */
    LOG_TILES,

    /** Starting a breastfeed, and where the running timer then shows. */
    BREASTFEED,

    /** The bottom bar: Trends, You, and a baby's own tab once there are two. */
    BOTTOM_BAR,

    /** Settings, and sharing with the other parent's phone. */
    SETTINGS,
}

/**
 * What this phone has stored about the tour, in the ordinary settings.
 *
 * Nothing stored is the commonest answer, and it means *never*: a phone set up
 * before the tour existed has a profile and no mark, and so does one restored
 * from its backup. Only finishing onboarding writes [PENDING], which is how
 * "only a phone that has just been set up" falls out without asking anything
 * else.
 */
enum class TourMark(val stored: String) {
    /** Onboarding has just finished, and the dashboard owes the tour. */
    PENDING("pending"),

    /** The tour has been offered: taken, skipped, or given way to a widget tap or a file. */
    DONE("done"),
}

/** The mark as [TourMark.stored] spells it; anything else reads as nothing stored. */
fun tourMark(stored: String?): TourMark? = TourMark.entries.firstOrNull { it.stored == stored }

/** What is asking for the tour. */
enum class TourTrigger {
    /** The dashboard coming up for the first time after a launch. */
    ARRIVAL,

    /** *Replay the tour*, in Settings. */
    REPLAY,
}

/**
 * The steps a dashboard listing [babyCount] babies can point at.
 *
 * A step that would point at nothing is left out rather than shown over an
 * empty space. With no baby yet there are no tiles and no feed buttons, and the
 * dashboard's own card already asks for a baby. With two or more, the tiles
 * move to each baby's own tab (BDR-9), which the bottom bar's step names.
 */
fun tourSteps(babyCount: Int): List<TourStep> = when {
    babyCount <= 0 -> listOf(TourStep.BOTTOM_BAR, TourStep.SETTINGS)
    babyCount == 1 -> TourStep.entries.toList()
    else -> listOf(TourStep.BREASTFEED, TourStep.BOTTOM_BAR, TourStep.SETTINGS)
}

/** The step after [step], or null when [step] was the last and the tour is over. */
fun tourStepAfter(step: TourStep, steps: List<TourStep>): TourStep? {
    val index = steps.indexOf(step)
    return if (index < 0) null else steps.getOrNull(index + 1)
}

/**
 * Whether the tour starts.
 *
 * On [TourTrigger.ARRIVAL] it starts only for a phone that owes it — one that
 * has just finished onboarding — and only when the launch was not for
 * something else. A widget tap or a Sprout file goes where it said, and that
 * wins (ADR-0008 for a second phone joining a household): the parent did not
 * open the app to be shown round it. A replay is the parent asking, so it
 * starts whatever is stored, on a phone that never had the tour as much as on
 * one that did.
 */
fun tourStarts(trigger: TourTrigger, mark: TourMark?, openedForSomethingElse: Boolean): Boolean =
    when (trigger) {
        TourTrigger.REPLAY -> true
        TourTrigger.ARRIVAL -> mark == TourMark.PENDING && !openedForSomethingElse
    }

/**
 * What is stored once the dashboard has come up: an owed tour is spent whether
 * or not it started.
 *
 * It is offered on the launch onboarding finished on and on no other, because
 * any later launch could be the one in the middle of a feed (BDR-6). A tour
 * that gave way to a widget tap does not come back on the next launch either;
 * Settings has it.
 */
fun tourMarkAfterArrival(mark: TourMark?): TourMark? =
    if (mark == TourMark.PENDING) TourMark.DONE else mark

/**
 * Whether a tour that is up gets out of the way.
 *
 * It does the moment the parent goes anywhere — a tile, a tab, Settings, or a
 * widget tap or a file arriving underneath it — because the tour is about the
 * dashboard and there is nothing left to point at. And it does the moment a
 * feed starts: someone feeding a baby is not reading cards. Only a feed that
 * *starts* counts, so a replay asked for with a feed already running is not cut
 * short by it.
 */
fun tourStepsAside(onDashboard: Boolean, feedWasRunning: Boolean, feedIsRunning: Boolean): Boolean =
    !onDashboard || (feedIsRunning && !feedWasRunning)
