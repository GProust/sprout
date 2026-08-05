package com.gproust.sprout.widget

import android.content.Context
import android.content.Intent
import android.appwidget.AppWidgetManager
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
    WidgetDiagnostics.record(
        context,
        "rendering ${appWidgetIds.size} widget(s): " +
            "session=${if (data.session != null) "running" else "none"}, " +
            "lastFeed=${data.feed?.type?.name?.lowercase() ?: "none"}, " +
            "baby=${if (data.babyName != null) "named" else "unknown"}",
    )
    for (id in appWidgetIds) {
        try {
            val views = glanceRemoteViews.compose(context, widgetSize(manager, id)) {
                GlanceTheme { SproutWidgetUi(data.session, data.feed, data.babyName) }
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
    WidgetDiagnostics.record(context, "render finished")
}

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
    val babyName: String?,
)

private suspend fun loadWidgetData(context: Context): WidgetData {
    val app = readForWidget(context, "app container") {
        context.applicationContext as SproutApplication
    }
    return WidgetData(
        session = readForWidget(context, "nursing session") { NursingSessionStore.load(context) },
        feed = readForWidget(context, "last feed") { app?.repository?.lastFeedForActiveBaby() },
        babyName = readForWidget(context, "active baby name") { app?.repository?.activeBabyName() },
    )
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

/**
 * Entry point declared in the manifest; the system talks to the widget through
 * this. A plain AppWidgetProvider rather than GlanceAppWidgetReceiver, because
 * we no longer route updates through Glance's session — see
 * [renderSproutWidgets].
 */
class SproutWidgetReceiver : AppWidgetProvider() {

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
