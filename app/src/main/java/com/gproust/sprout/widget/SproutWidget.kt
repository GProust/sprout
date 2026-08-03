package com.gproust.sprout.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
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
import com.gproust.sprout.ui.common.formatTime
import com.gproust.sprout.ui.common.isSameDay

/**
 * The breast to offer first at the next feed alternates, so what a nursing
 * parent wants at a glance is where the last feed *ended*: the side of the
 * last recorded stretch when per-segment timing exists, otherwise the
 * session-level side (which may be BOTH on older entries).
 */
fun lastNursedSide(feed: FeedingEntity): BreastSide? =
    feed.segments.lastOrNull()?.side ?: feed.side

/**
 * Home-screen widget showing the side and start time of the active baby's
 * last breastfeed. Re-rendered by the repository after every relevant write
 * (plus an hourly system refresh so "today vs earlier" stays honest), and
 * opens the app when tapped.
 */
class SproutWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as SproutApplication
        val feed = app.repository.lastBreastFeedForActiveBaby()
        provideContent {
            GlanceTheme {
                WidgetContent(feed)
            }
        }
    }
}

@Composable
private fun WidgetContent(feed: FeedingEntity?) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
            lastNursedSide(feed)?.let { side ->
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
            val now = System.currentTimeMillis()
            Text(
                text = if (isSameDay(feed.startTime, now)) {
                    formatTime(feed.startTime)
                } else {
                    formatDateTime(context, feed.startTime)
                },
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp),
            )
        }
    }
}

/** Entry point declared in the manifest; the system talks to the widget through this. */
class SproutWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SproutWidget()
}
