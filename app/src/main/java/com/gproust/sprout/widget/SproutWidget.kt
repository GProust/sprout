package com.gproust.sprout.widget

import android.content.Context
import android.content.Intent
import android.appwidget.AppWidgetManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.gproust.sprout.MainActivity
import com.gproust.sprout.R
import com.gproust.sprout.SproutApplication
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.ui.common.formatDateTime
import com.gproust.sprout.ui.feeding.NursingSession
import com.gproust.sprout.ui.feeding.NursingSessionStore
import com.gproust.sprout.ui.navigation.Routes
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Draws every placed widget and hands the result to the launcher.
 *
 * Deliberately *not* GlanceAppWidget.update(). On minified release builds that
 * accepted the update, returned cleanly and then silently never started its
 * session — provideGlance was never reached, on any device tested, with no
 * exception anywhere to explain it. Composing the same UI to RemoteViews and
 * inflating it worked fine in those very builds, so this keeps the Compose UI
 * and only replaces the delivery: compose, then push through AppWidgetManager
 * ourselves.
 *
 * The trade is that nothing recomposes on its own. That costs us little,
 * because every source of change already calls back here: the repository after
 * any widget-visible write, the feeding screen when a session starts or stops,
 * and the system every half hour so "2 h 15 min ago" doesn't drift.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
suspend fun renderSproutWidgets(context: Context, appWidgetIds: IntArray) {
    if (appWidgetIds.isEmpty()) return
    val manager = AppWidgetManager.getInstance(context)
    val data = loadWidgetData(context)
    val lastBreast = data.lastBreast
        ?.let { firstNursedSide(it)?.name?.lowercase() ?: "side unknown" }
        ?: "none in 24 h"
    WidgetDiagnostics.record(
        context,
        "rendering ${appWidgetIds.size} widget(s): " +
            "session=${if (data.session != null) "running" else "none"}, " +
            "lastFeed=${data.feed?.type?.name?.lowercase() ?: "none"}, " +
            "lastBreast=$lastBreast, " +
            "baby=${if (data.babyName != null) "named" else "unknown"}",
    )
    for (id in appWidgetIds) {
        try {
            val views = glanceRemoteViews.compose(context, widgetSize(manager, id)) {
                GlanceTheme { SproutWidgetUi(data.session, data.feed, data.lastBreast, data.babyName) }
            }.remoteViews
            manager.updateAppWidget(id, views)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Say so on the home screen rather than leave the launcher showing
            // its loading spinner for ever, which is how this hid for so long.
            WidgetDiagnostics.record(context, "could not draw widget $id", e)
            runCatching {
                manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_error))
            }
        }
    }
    scheduleWidgetTick(context, data)
    WidgetDiagnostics.record(context, "render finished")
}

/**
 * Keeps "20 min ago" honest.
 *
 * The elapsed time is baked into the RemoteViews when they are drawn, and the
 * system only refreshes a widget every 30 minutes (its floor), so a feed logged
 * a quarter of an hour ago still read "just now". This lines up a re-draw on
 * the next minute boundary, which is exactly when the text can first change.
 *
 * Deliberately a non-wakeup alarm: it fires while the phone is awake and is
 * deferred while it sleeps, which is the right trade for something only worth
 * updating when somebody might be looking at it.
 */
private fun scheduleWidgetTick(context: Context, data: WidgetData) {
    val alarms = context.getSystemService(AlarmManager::class.java) ?: return
    val pending = tickIntent(context)
    // Past two days the widget shows an absolute date, which never goes stale,
    // so there is nothing left to tick for.
    val ticking = data.session != null ||
        data.feed?.startTime?.let { System.currentTimeMillis() - it < RELATIVE_TIME_LIMIT_MS } == true
    if (!ticking) {
        alarms.cancel(pending)
        return
    }
    val now = System.currentTimeMillis()
    alarms.set(AlarmManager.RTC, now - (now % 60_000L) + 60_000L, pending)
}

internal fun cancelWidgetTick(context: Context) {
    context.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(context))
}

private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, SproutWidgetReceiver::class.java).setAction(SproutWidgetReceiver.ACTION_TICK),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

/** Beyond this the widget shows a date rather than an elapsed time. */
private const val RELATIVE_TIME_LIMIT_MS = 48L * 60L * 60L * 1000L

/** One-stop widget refresh, called wherever widget-visible data changes. */
suspend fun updateSproutWidget(context: Context) {
    val ids = placedWidgetIds(context)
    WidgetDiagnostics.record(context, "app asked the widget to refresh (${ids.size} placed)")
    renderSproutWidgets(context, ids)
}

internal fun placedWidgetIds(context: Context): IntArray =
    AppWidgetManager.getInstance(context).getAppWidgetIds(
        ComponentName(context, SproutWidgetReceiver::class.java),
    )

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
private val glanceRemoteViews = GlanceRemoteViews()

/**
 * The size the launcher has actually given this widget, so the composition is
 * laid out for the space it will occupy rather than a guess.
 */
private fun widgetSize(manager: AppWidgetManager, appWidgetId: Int): DpSize {
    val options = manager.getAppWidgetOptions(appWidgetId)
    val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
    val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
    // A widget that has never been measured reports 0; fall back to the 2x1
    // the provider asks for rather than composing into nothing.
    return DpSize(
        (if (width > 0) width else DEFAULT_WIDGET_WIDTH_DP).dp,
        (if (height > 0) height else DEFAULT_WIDGET_HEIGHT_DP).dp,
    )
}

private const val DEFAULT_WIDGET_WIDTH_DP = 180
private const val DEFAULT_WIDGET_HEIGHT_DP = 110

/** Everything one render needs, read once. */
private class WidgetData(
    val session: NursingSession?,
    val feed: FeedingEntity?,
    val lastBreast: FeedingEntity?,
    val babyName: String?,
)

private suspend fun loadWidgetData(context: Context): WidgetData {
    val app = readForWidget(context, "app container") {
        context.applicationContext as SproutApplication
    }
    return WidgetData(
        session = readForWidget(context, "nursing session") { NursingSessionStore.load(context) },
        feed = readForWidget(context, "last feed") { app?.repository?.lastFeedForActiveBaby() },
        lastBreast = readForWidget(context, "last breastfeed") {
            app?.repository?.lastBreastFeedForActiveBaby(
                System.currentTimeMillis() - LAST_BREAST_WINDOW_MS,
            )
        },
        babyName = readForWidget(context, "active baby name") { app?.repository?.activeBabyName() },
    )
}

/**
 * Which breast the last feed *started* on — the side the next feed alternates
 * away from, which is the thing being decided at 3 a.m.
 *
 * A session that went left, then right, still reads "left": what matters is
 * where it began, not where it happened to stop. Falls back to the
 * session-level side when there is no per-segment timing (which may be BOTH on
 * older entries).
 */
fun firstNursedSide(feed: FeedingEntity): BreastSide? =
    feed.segments.firstOrNull()?.side ?: feed.side

/** How far back the widget still cares which breast the last breastfeed began on. */
internal const val LAST_BREAST_WINDOW_MS = 24L * 60L * 60L * 1000L

/**
 * The reminder line "Last breast: Left · 3 h ago", or null when there is
 * nothing to remind.
 *
 * A bottle (or a spoon of purée) given between two nursings used to take the
 * side with it: the widget only ever showed the last feed, so the answer to
 * "which breast do I start on?" disappeared behind the bottle. This keeps it
 * on screen for a day, alongside whatever the last feed happened to be.
 *
 * Null when the last feed *is* the breastfeed — its side is already the big
 * line — when nothing was nursed in the last 24 hours, or when that
 * breastfeed never recorded a side.
 */
internal fun lastBreastLine(
    context: Context,
    feed: FeedingEntity?,
    lastBreast: FeedingEntity?,
    now: Long,
): String? {
    if (feed == null || feed.type == FeedType.BREAST) return null
    // Re-checked here as well as in the query: a render can be a tick or two
    // older than the data it was handed.
    val breast = lastBreast?.takeIf { now - it.startTime <= LAST_BREAST_WINDOW_MS } ?: return null
    val side = firstNursedSide(breast) ?: return null
    return context.getString(
        R.string.widget_last_breast,
        context.getString(sideLabel(side)),
        widgetTimeAgo(context, breast.startTime, now),
    )
}

/**
 * The widget's time line: elapsed time since the feed with explicit units
 * ("2 h 15 min ago"). Once the last feed is over two days old an hour count
 * stops being readable, so it falls back to the absolute date + time.
 */
internal fun widgetTimeAgo(context: Context, epochMillis: Long, now: Long): String {
    val totalMin = (now - epochMillis) / 60_000L
    val hours = totalMin / 60
    val minutes = totalMin % 60
    return when {
        totalMin < 1 -> context.getString(R.string.relative_just_now)
        hours < 1 -> context.getString(R.string.widget_ago_minutes, minutes.toInt())
        hours >= 48 -> formatDateTime(context, epochMillis)
        minutes == 0L -> context.getString(R.string.widget_ago_hours, hours.toInt())
        else -> context.getString(R.string.widget_ago_hours_minutes, hours.toInt(), minutes.toInt())
    }
}

/**
 * Reads one piece of widget data, degrading to null if it fails. A widget
 * showing its empty state is recoverable; one that threw on the way to being
 * drawn leaves the launcher on a loading spinner instead. Catches Throwable,
 * not Exception: a member removed by shrinking arrives as an Error.
 */
private suspend fun <T> readForWidget(
    context: Context,
    what: String,
    read: suspend () -> T,
): T? =
    try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        WidgetDiagnostics.record(context, "could not read the $what; showing the empty state", e)
        null
    }

/** The widget's root content: the live session when one runs, else the last feed. */
@Composable
internal fun SproutWidgetUi(
    session: NursingSession?,
    feed: FeedingEntity?,
    lastBreast: FeedingEntity?,
    babyName: String?,
) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(openFeedingScreen(context)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (session != null) {
            NursingLines(session, babyName)
        } else {
            LastFeedLines(feed, lastBreast, babyName)
        }
    }
}

/**
 * The small top line: who, then what — "Lea - Bottle". The baby comes first
 * because with twins that is the thing you are squinting at the widget to
 * find out. Falls back to the detail alone when no baby name is known.
 */
@Composable
private fun CaptionText(babyName: String?, detail: String) {
    val context = LocalContext.current
    val separator = context.getString(R.string.feeding_detail_separator)
    val text = listOfNotNull(babyName?.takeIf { it.isNotBlank() }, detail).joinToString(separator)
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
    )
}

/** Tapping the widget lands directly on the Feeding screen. */
private fun openFeedingScreen(context: Context) = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(MainActivity.EXTRA_ROUTE, Routes.FEEDING),
)

/** A breastfeed being timed right now: current side + live chronometer. */
@Composable
private fun NursingLines(session: NursingSession, babyName: String?) {
    val context = LocalContext.current
    CaptionText(babyName, context.getString(R.string.feeding_breastfeeding))
    SideText(session.currentSide)
    // Glance has no chronometer, so embed classic RemoteViews: the launcher
    // ticks it every second with no widget refreshes at all.
    val timer = RemoteViews(context.packageName, R.layout.widget_nursing_timer).apply {
        setChronometer(
            R.id.widgetNursingTimer,
            SystemClock.elapsedRealtime() - (System.currentTimeMillis() - session.sessionStart),
            null,
            true,
        )
    }
    AndroidRemoteViews(remoteViews = timer)
}

/** The finished-feed summary: what kind of feed, its one detail, and how long ago. */
@Composable
private fun LastFeedLines(feed: FeedingEntity?, lastBreast: FeedingEntity?, babyName: String?) {
    val context = LocalContext.current
    if (feed == null) {
        CaptionText(babyName, context.getString(R.string.widget_label))
        Text(
            text = context.getString(R.string.widget_no_feed),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
        )
        return
    }
    CaptionText(babyName, context.getString(feedTypeLabel(feed.type)))
    // Solids have no one number worth shouting, so they simply skip the big
    // line and leave the widget with a caption and a time.
    feedHeadline(context, feed)?.let { HeadlineText(it) }
    val now = System.currentTimeMillis()
    Text(
        text = widgetTimeAgo(context, feed.startTime, now),
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp),
    )
    // A bottle or some solids on top of the day's nursing: say which breast the
    // last breastfeed started on, so the alternation survives the interruption.
    lastBreastLine(context, feed, lastBreast, now)?.let {
        Text(
            text = it,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
    }
}

@StringRes
private fun feedTypeLabel(type: FeedType): Int = when (type) {
    FeedType.BREAST -> R.string.feed_type_breast
    FeedType.BOTTLE -> R.string.feed_type_bottle
    FeedType.SOLID -> R.string.feed_type_solid
}

/**
 * The single detail worth the widget's big line, which differs by feed type:
 * which breast it ended on, or how much came out of the bottle. Null when the
 * feed has nothing of the sort to say.
 */
private fun feedHeadline(context: Context, feed: FeedingEntity): String? = when (feed.type) {
    FeedType.BREAST -> firstNursedSide(feed)?.let { context.getString(sideLabel(it)) }
    FeedType.BOTTLE -> feed.amountMl?.let { context.getString(R.string.feeding_amount_ml, it) }
    FeedType.SOLID -> null
}

@StringRes
private fun sideLabel(side: BreastSide): Int = when (side) {
    BreastSide.LEFT -> R.string.side_left
    BreastSide.RIGHT -> R.string.side_right
    BreastSide.BOTH -> R.string.side_both
}

@Composable
private fun SideText(side: BreastSide) {
    HeadlineText(LocalContext.current.getString(sideLabel(side)))
}

/** The widget's one big line, whatever it happens to be saying. */
@Composable
private fun HeadlineText(text: String) {
    Text(
        text = text,
        style = TextStyle(
            color = GlanceTheme.colors.primary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}

/**
 * Entry point declared in the manifest; the system talks to the widget through
 * this. A plain AppWidgetProvider rather than GlanceAppWidgetReceiver, because
 * we no longer route updates through Glance's session — see
 * [renderSproutWidgets].
 */
class SproutWidgetReceiver : AppWidgetProvider() {

    companion object {
        /** Our own minute tick, so the elapsed time doesn't go stale. */
        const val ACTION_TICK = "com.gproust.sprout.widget.TICK"
    }

    override fun onEnabled(context: Context) {
        WidgetDiagnostics.record(context, "receiver: first widget added")
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetDiagnostics.record(context, "receiver: onUpdate for ${appWidgetIds.size} widget(s)")
        renderInBackground(context, appWidgetIds)
    }

    /** Resizing changes the size we compose for, so redraw at the new one. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        WidgetDiagnostics.record(context, "receiver: widget $appWidgetId resized")
        renderInBackground(context, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetDiagnostics.record(context, "receiver: ${appWidgetIds.size} widget(s) removed")
    }

    /** Nothing left on the home screen to keep up to date. */
    override fun onDisabled(context: Context) {
        WidgetDiagnostics.record(context, "receiver: last widget removed")
        cancelWidgetTick(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TICK) {
            renderInBackground(context, placedWidgetIds(context))
            return
        }
        super.onReceive(context, intent)
    }

    /**
     * Drawing reads the database, so it can't happen on the broadcast's thread.
     * goAsync keeps the receiver alive across the coroutine; the result is
     * always finished, or the system would count it as a leak.
     */
    private fun renderInBackground(context: Context, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                renderSproutWidgets(context, appWidgetIds)
            } catch (e: Throwable) {
                WidgetDiagnostics.record(context, "receiver: render failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
