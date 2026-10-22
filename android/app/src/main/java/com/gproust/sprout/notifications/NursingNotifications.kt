package com.gproust.sprout.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.gproust.sprout.MainActivity
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.ui.common.formatDuration
import com.gproust.sprout.ui.feeding.NursingActions
import com.gproust.sprout.ui.feeding.NursingControl
import com.gproust.sprout.ui.feeding.NursingSession
import com.gproust.sprout.ui.feeding.NursingSessionStore
import com.gproust.sprout.ui.feeding.nursingControls
import com.gproust.sprout.ui.navigation.Routes
import com.gproust.sprout.ui.settings.NursingNotificationSettings

/**
 * A running breastfeed in the notifications, the way a stopwatch shows one, with
 * the timer's own buttons (BDR-24).
 *
 * **Nothing runs.** A session is timestamps, and the system draws the clock
 * itself from [NotificationCompat.Builder.setWhen] — so there is no foreground
 * service (ADR-0010 refused one, and `AttackSurfaceTest` would refuse the
 * permission it needs), no ticking job and nothing kept awake. The notification
 * is redrawn when the feed changes and only then: started, paused, resumed,
 * switched, saved, and when the app or the phone comes up with a feed still in
 * the store.
 *
 * **One per running feed** (BDR-23), each with its own buttons. Twins feeding
 * together are two notifications, told apart on a locked screen as *Feed 1* and
 * *Feed 2*: the baby's name is in the private version only, which the lock
 * screen shows only if the parent has said it may.
 *
 * **Swiping one away ends nothing.** Android 14 lets a parent dismiss an ongoing
 * notification that is not a service's, and that is fine: there is no delete
 * intent, the feed carries on, and the next change to it posts it again.
 *
 * Opt-in, from Settings, where the notification permission is asked for —
 * never at the moment a feed starts, which is the worst moment to interrupt.
 */
object NursingNotifications {
    const val CHANNEL_ID = "nursing_session"

    /** What the notification's buttons send, back to [FeedingReminderReceiver]. */
    const val ACTION_CONTROL = "com.gproust.sprout.NURSING_CONTROL"
    const val EXTRA_CONTROL = "nursing_control"
    const val EXTRA_BABY_UID = "baby_uid"

    /**
     * One id for every running feed, told apart by tag (the baby's uid). Clear
     * of the reminders' id ranges, which count up from 0, 1, 2 and 3 million.
     */
    private const val NOTIFICATION_ID = 5_000_000
    private const val TAG_PREFIX = "nursing:"

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.nursing_channel_name),
                    // Low: no sound and no heads-up. It is a clock the parent
                    // asked to see, not something to be told about.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.nursing_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    /** The tag a feed's notification is posted under. */
    fun tagFor(session: NursingSession): String = TAG_PREFIX + session.babyUid.orEmpty()

    /**
     * Posts, redraws or takes away the notifications so that they show exactly
     * the feeds running now — or none, when the switch is off or the permission
     * was never given.
     */
    suspend fun refresh(context: Context, repository: SproutRepository) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        val sessions = NursingSessionStore.loadAll(context)
        val shown = if (NursingNotificationSettings.isEnabled(context)) sessions else emptyList()
        val keep = shown.map(::tagFor).toSet()
        manager.activeNotifications
            .filter { it.id == NOTIFICATION_ID }
            .mapNotNull { it.tag }
            .filter { it.startsWith(TAG_PREFIX) && it !in keep }
            .forEach { manager.cancel(it, NOTIFICATION_ID) }
        if (shown.isEmpty()) return

        ensureChannel(context)
        val names = repository.babyNamesByUid()
        val now = System.currentTimeMillis()
        shown.forEachIndexed { index, session ->
            post(
                context,
                session,
                build(context, session, session.babyUid?.let { names[it] }, index + 1, shown.size, now),
            )
        }
    }

    /**
     * What a button on the notification asked for, done — the same call the
     * timer screen makes. A second tap, or a tap on a notification a step behind
     * the feed, changes nothing; *Stop & save* twice saves one feed.
     */
    suspend fun handle(context: Context, repository: SproutRepository, intent: Intent) {
        val control = intent.getStringExtra(EXTRA_CONTROL)
            ?.let { name -> NursingControl.entries.firstOrNull { it.name == name } }
            ?: return
        val babyUid = intent.getStringExtra(EXTRA_BABY_UID)
        if (control == NursingControl.STOP) {
            NursingActions.take(context, babyUid)?.let { NursingActions.save(context, repository, it) }
        } else {
            NursingActions.apply(context, babyUid, control)
        }
        NursingActions.refreshOutside(context, repository)
    }

    private fun post(context: Context, session: NursingSession, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(tagFor(session), NOTIFICATION_ID, notification)
    }

    /**
     * The notification for one running feed: *Léa · Feeding · Left* and the
     * time at the breast, ticking; on a break, a still figure instead — the
     * system cannot be asked to stop a chronometer, the same reason the widget
     * shows a stopped clock (BDR-17). [number] of [count] names the feed on a
     * locked screen when there is more than one.
     */
    internal fun build(
        context: Context,
        session: NursingSession,
        babyName: String?,
        number: Int,
        count: Int,
        now: Long,
    ): Notification {
        val state = context.getString(stateRes(session))
        val title = if (babyName != null) {
            context.getString(R.string.home_live_nursing_named, babyName, state)
        } else {
            context.getString(R.string.home_live_nursing, state)
        }
        // No name on a locked screen: just enough to tell two feeds apart.
        val publicTitle = if (count > 1) {
            context.getString(R.string.nursing_notif_feed_number, number, state)
        } else {
            context.getString(R.string.home_live_nursing, state)
        }
        val public = builder(context, session, now, number).setContentTitle(publicTitle).build()
        return builder(context, session, now, number)
            .setContentTitle(title)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
    }

    /** Everything the private and the public version share, buttons included. */
    private fun builder(
        context: Context,
        session: NursingSession,
        now: Long,
        number: Int,
    ): NotificationCompat.Builder {
        // Time at the breast, never `now - segmentStart` (BDR-17).
        val nursed = session.nursedMs(now)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // The running feed stays on this phone (BDR-17): not mirrored to a
            // watch or anything else the phone is paired with.
            .setLocalOnly(true)
            .setSortKey(number.toString())
            .setContentIntent(openApp(context))
            .apply {
                if (session.isPaused) {
                    setUsesChronometer(false)
                    setShowWhen(false)
                    setContentText(
                        context.getString(R.string.nursing_notif_on_break, formatDuration(context, nursed)),
                    )
                } else {
                    // The system counts up from here: a clock started as long
                    // ago as the baby has been at the breast.
                    setWhen(now - nursed)
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
                // No icons: Android has not drawn action icons since Nougat.
                nursingControls(session).forEach { control ->
                    addAction(0, context.getString(labelRes(control)), controlIntent(context, session, control))
                }
            }
    }

    @StringRes
    private fun stateRes(session: NursingSession): Int = when {
        session.isPaused -> R.string.feeding_paused
        session.currentSide == BreastSide.RIGHT -> R.string.side_right
        else -> R.string.side_left
    }

    @StringRes
    private fun labelRes(control: NursingControl): Int = when (control) {
        NursingControl.PAUSE -> R.string.feeding_pause
        NursingControl.SWITCH -> R.string.nursing_switch_side
        NursingControl.RESUME_LEFT -> R.string.feeding_resume_left
        NursingControl.RESUME_RIGHT -> R.string.feeding_resume_right
        NursingControl.STOP -> R.string.feeding_stop_save
    }

    /**
     * A button's intent: a broadcast to the app's own, non-exported receiver, so
     * nothing outside Sprout can send one, and no screen has to open — the point
     * is not having to unlock the phone.
     *
     * The data URI makes each feed's each button its own `PendingIntent`;
     * `PendingIntent` tells intents apart by action and data, never by extras.
     */
    private fun controlIntent(context: Context, session: NursingSession, control: NursingControl): PendingIntent {
        val intent = Intent(context, FeedingReminderReceiver::class.java).apply {
            action = ACTION_CONTROL
            data = Uri.Builder()
                .scheme("sprout")
                .authority("nursing")
                .appendPath(session.babyUid.orEmpty())
                .appendPath(control.name)
                .build()
            putExtra(EXTRA_CONTROL, control.name)
            session.babyUid?.let { putExtra(EXTRA_BABY_UID, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Tapping the notification itself opens the dashboard, where every running
     * feed has a row with its baby's name — with twins, the one place that
     * shows them all. The widget's own action, so it is never mistaken for a
     * file to open.
     */
    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_ROUTE)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_ROUTE, Routes.HOME),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
