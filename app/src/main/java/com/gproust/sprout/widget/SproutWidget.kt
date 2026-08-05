package com.gproust.sprout.widget

import android.content.Context
import android.content.Intent
import android.appwidget.AppWidgetManager
import android.os.SystemClock
import android.widget.RemoteViews
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
 * Home-screen widget for breastfeeding. Idle, it shows the side of the active
 * baby's last breastfeed and how long ago it was; while a session is being
 * timed it shows the current side with a live ticking timer. Re-rendered by
 * the repository/ViewModel after every relevant change, plus a half-hourly
 * system refresh (Android's minimum) so the elapsed time doesn't drift too
 * far between feeds. Tapping it opens the app on the Feeding screen.
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
        val initialFeed = readForWidget(context, "last breastfeed") {
            app?.repository?.lastBreastFeedForActiveBaby()
        }
        WidgetDiagnostics.record(
            context,
            "data read: session=${if (initialSession != null) "running" else "none"}, " +
                "lastFeed=${if (initialFeed != null) "found" else "none"}",
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
            val feed by (app?.repository?.lastBreastFeedForActiveBabyFlow ?: flowOf(initialFeed))
                .collectAsState(initial = initialFeed)
            GlanceTheme {
                SproutWidgetUi(session, feed)
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
internal fun SproutWidgetUi(session: NursingSession?, feed: FeedingEntity?) {
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
            NursingLines(session)
        } else {
            LastFeedLines(feed)
        }
    }
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
private fun NursingLines(session: NursingSession) {
    val context = LocalContext.current
    Text(
        text = context.getString(R.string.feeding_breastfeeding),
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
    )
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

/** The finished-feed summary: last side + elapsed time with h/min units. */
@Composable
private fun LastFeedLines(feed: FeedingEntity?) {
    val context = LocalContext.current
    Text(
        text = context.getString(R.string.widget_label),
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
    )
    if (feed == null) {
        Text(
            text = context.getString(R.string.widget_no_breastfeed),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
        )
    } else {
        lastNursedSide(feed)?.let { SideText(it) }
        Text(
            text = widgetTimeAgo(context, feed.startTime, System.currentTimeMillis()),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp),
        )
    }
}

@Composable
private fun SideText(side: BreastSide) {
    val context = LocalContext.current
    Text(
        text = context.getString(
            when (side) {
                BreastSide.LEFT -> R.string.side_left
                BreastSide.RIGHT -> R.string.side_right
                BreastSide.BOTH -> R.string.side_both
            },
        ),
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
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetDiagnostics.record(context, "receiver: ${appWidgetIds.size} widget(s) removed")
        super.onDeleted(context, appWidgetIds)
    }
}
