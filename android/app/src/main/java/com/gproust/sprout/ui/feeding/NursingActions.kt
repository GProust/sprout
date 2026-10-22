package com.gproust.sprout.ui.feeding

import android.content.Context
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.notifications.FeedingReminders
import com.gproust.sprout.notifications.NursingNotifications
import com.gproust.sprout.widget.updateSproutWidget

/**
 * Every act on a running breastfeed, from wherever it is tapped: the timer
 * screen, the dashboard, and the notification that shows the feed outside the
 * app (BDR-24).
 *
 * One place, so that *Pause* in the notification and *Pause* on the timer are
 * the same call on the same store, and *Stop & save* from the lock screen saves
 * the feed the timer would have saved — through [SproutRepository], the only
 * thing that stamps a row. Each act then redraws everything that shows a
 * running feed: the widget and the notification.
 *
 * The store changes synchronously and before anything else, exactly as it did
 * when this lived in the feeding screen's view model: [take] in particular is
 * what makes a second *Stop & save* — a double tap, or the notification and the
 * timer both — a no-op rather than a second feed.
 */
object NursingActions {

    /**
     * Begin timing a breastfeed for [babyUid] on [side]. A feed already running
     * for that baby is handed back untouched; another baby's is no obstacle
     * (BDR-23).
     */
    fun start(context: Context, side: BreastSide, babyUid: String?, now: Long = System.currentTimeMillis()) =
        NursingSessionStore.startIfIdle(
            context,
            NursingSession(sessionStart = now, currentSide = side, segmentStart = now, babyUid = babyUid),
        )

    /**
     * [control] on [babyUid]'s running feed, and nothing else. A control that no
     * longer fits — a second *Pause*, a *Switch* left on screen while the feed
     * went on a break — changes nothing ([after]). [NursingControl.STOP] is not
     * one of these: it is [take] then [save].
     */
    fun apply(
        context: Context,
        babyUid: String?,
        control: NursingControl,
        now: Long = System.currentTimeMillis(),
    ): NursingSession? = NursingSessionStore.update(context, babyUid) { it.after(control, now) }

    /**
     * Takes [babyUid]'s feed away so that it can be saved — the only way one
     * ends. Null when there was nothing left to take, which is what a second
     * screen, a second tap or a stale notification is handed.
     */
    fun take(context: Context, babyUid: String?): NursingSession? =
        NursingSessionStore.consume(context, babyUid)

    /**
     * Saves a feed [take] handed over, to the baby it was started for, and moves
     * that baby's feeding reminder on. Returns the baby it went to, or null when
     * there was nobody to give it to.
     */
    suspend fun save(
        context: Context,
        repository: SproutRepository,
        session: NursingSession,
        notes: String = "",
        now: Long = System.currentTimeMillis(),
    ): Long? {
        val babyId = repository.addTimedFeeding(session.babyUid, session.toFeeding(now, notes))
            ?: return null
        FeedingReminders.rescheduleForBaby(context, repository, babyId)
        return babyId
    }

    /** Redraws everything that shows a running feed outside the app's own screens. */
    suspend fun refreshOutside(context: Context, repository: SproutRepository) {
        updateSproutWidget(context)
        NursingNotifications.refresh(context, repository)
    }
}
