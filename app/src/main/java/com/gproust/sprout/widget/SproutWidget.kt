package com.gproust.sprout.widget

import android.content.Context
import android.content.Intent
import android.appwidget.AppWidgetManager
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
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
import kotlinx.coroutines.flow.flowOf

/** One-stop widget refresh, called wherever widget-visible data changes. */
suspend fun updateSproutWidget(context: Context) {
    WidgetDiagnostics.record(context, "app asked the widget to refresh")
    SproutWidget().updateAll(context)
}

/**
 * The breast to offer first at the next feed alternates, so what a nursing
 * parent wants at a glance is where the last feed *ended*: the side of the
 * last recorded stretch when per-segment timing exists, otherwise the
 * session-level side (which may be BOTH on older entries).
 */
fun lastNursedSide(feed: FeedingEntity): BreastSide? =
    feed.segments.lastOrNull()?.side ?: feed.side

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
 * Home-screen widget for feeding. Idle, it shows the active baby's name, the
 * kind of their last feed and how long ago it was, plus the one detail that
 * kind carries - which breast it ended on, or how much came out of the
 * bottle. While a breastfeed is being timed it shows the current side with a
 * live ticking timer. Re-rendered by the repository/ViewModel after every
 * relevant change, plus a half-hourly system refresh (Android's minimum) so
 * the elapsed time doesn't drift too far between feeds. Tapping it opens the
 * app on the Feeding screen.
 */
class SproutWidget : GlanceAppWidget(errorUiLayout = R.layout.widget_error) {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetDiagnostics.record(context, "provideGlance started")
        val app = readForWidget(context, "app container") {
            context.applicationContext as SproutApplication
        }
        // Initial values, so the first frame is right even before the flows
        // below have emitted.
        val initialSession = readForWidget(context, "nursing session") {
            NursingSessionStore.load(context)
        }
        val initialFeed = readForWidget(context, "last feed") {
            app?.repository?.lastFeedForActiveBaby()
        }
        val initialBabyName = readForWidget(context, "active baby name") {
            app?.repository?.activeBabyName()
        }
        WidgetDiagnostics.record(
            context,
            "data read: session=${if (initialSession != null) "running" else "none"}, " +
                "lastFeed=${initialFeed?.type?.name?.lowercase() ?: "none"}, " +
                "baby=${if (initialBabyName != null) "named" else "unknown"}",
        )
        provideContent {
            // Observed *inside* the composition on purpose. update()/updateAll()
            // don't restart provideGlance while it is still running, so reading
            // once above and closing over the result meant every later refresh
            // just redrew the same stale values — feeds logged after the widget
            // was placed never showed, and a session that ended stayed on
            // screen. Once the composition is torn down the next refresh does
            // restart provideGlance, which re-reads the initial values above,
            // so the two together cover both windows.
            val session by NursingSessionStore.observe(context)
                .collectAsState(initial = initialSession)
            val feed by (app?.repository?.lastFeedForActiveBabyFlow ?: flowOf(initialFeed))
                .collectAsState(initial = initialFeed)
            val babyName by (app?.repository?.activeBabyNameFlow ?: flowOf(initialBabyName))
                .collectAsState(initial = initialBabyName)
            GlanceTheme {
                SproutWidgetUi(session, feed, babyName)
            }
        }
        // provideContent suspends until the composition closes, so reaching
        // here means the launcher tore the widget down, not that we failed.
        WidgetDiagnostics.record(context, "composition closed")
    }
}

/**
 * Reads one piece of widget data, degrading to null if it fails. Everything
 * [SproutWidget.provideGlance] loads happens *before* `provideContent`, so a
 * throw there means no content is ever emitted and the widget sits on its
 * initial loading layout for good — no error UI, and no retry until the next
 * half-hourly refresh. Showing the empty state is recoverable; a permanent
 * spinner isn't.
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
    } catch (e: Exception) {
        WidgetDiagnostics.record(context, "could not read the $what; showing the empty state", e)
        null
    }

/** The widget's root content: the live session when one runs, else the last feed. */
@Composable
internal fun SproutWidgetUi(
    session: NursingSession?,
    feed: FeedingEntity?,
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
            LastFeedLines(feed, babyName)
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
private fun LastFeedLines(feed: FeedingEntity?, babyName: String?) {
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
    Text(
        text = widgetTimeAgo(context, feed.startTime, System.currentTimeMillis()),
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp),
    )
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
    FeedType.BREAST -> lastNursedSide(feed)?.let { context.getString(sideLabel(it)) }
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

/** Entry point declared in the manifest; the system talks to the widget through this. */
class SproutWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SproutWidget()

    // Recorded so a diagnostics report can distinguish "the launcher never
    // asked us to update" from "it asked and we failed" — those point at
    // completely different causes.
    override fun onEnabled(context: Context) {
        WidgetDiagnostics.record(context, "receiver: first widget added")
        super.onEnabled(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetDiagnostics.record(context, "receiver: onUpdate for ${appWidgetIds.size} widget(s)")
        // The gap between here and provideGlance is where Glance starts its
        // session, and it was a blind spot: reports showed onUpdate firing over
        // and over with nothing behind it and no error to explain the silence.
        try {
            super.onUpdate(context, appWidgetManager, appWidgetIds)
            WidgetDiagnostics.record(context, "receiver: handed off to Glance")
        } catch (e: Throwable) {
            WidgetDiagnostics.record(context, "receiver: Glance refused the update", e)
            throw e
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetDiagnostics.record(context, "receiver: ${appWidgetIds.size} widget(s) removed")
        super.onDeleted(context, appWidgetIds)
    }
}
