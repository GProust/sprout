package com.gproust.sprout.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceRemoteViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Breadcrumbs for the home-screen widget, and a self-test that reproduces
 * whatever the widget does wrong from inside the app.
 *
 * The widget runs in a broadcast receiver the user never sees, so when it
 * misbehaves there is nothing to look at: no screen, no error, and on a Play
 * build no practical way to get at logcat. Every step of a widget update
 * therefore leaves a timestamped line here (SharedPreferences, so it survives
 * the process dying between updates), and Settings → "Widget diagnostics"
 * reads it back with a Share button.
 *
 * Nothing is ever sent anywhere on its own: the report is plain text the user
 * chooses to share, which keeps this in line with Sprout being fully offline.
 */
object WidgetDiagnostics {

    /** logcat tag — `adb logcat -s SproutWidget` when a cable is an option. */
    const val TAG = "SproutWidget"

    private const val PREFS = "widget_diagnostics"
    private const val KEY_LOG = "log"

    /** Enough to cover several update cycles without growing without bound. */
    private const val MAX_ENTRIES = 40

    /** Entries are multi-line, so they need a separator that can't occur in one. */
    private const val ENTRY_SEPARATOR = "\u001E"

    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    /**
     * Notes one step of a widget update, to logcat and to disk. Never throws:
     * a diagnostic that can break the thing it is diagnosing is worse than no
     * diagnostic at all.
     */
    fun record(context: Context, message: String, error: Throwable? = null) {
        try {
            if (error == null) Log.i(TAG, message) else Log.e(TAG, message, error)
            val line = buildString {
                append(stamp()).append("  ").append(message)
                if (error != null) append("\n    ").append(describe(error))
            }
            synchronized(lock) {
                val p = prefs(context)
                val kept = (p.getString(KEY_LOG, "").orEmpty())
                    .split(ENTRY_SEPARATOR)
                    .filter { it.isNotBlank() }
                    .takeLast(MAX_ENTRIES - 1)
                p.edit()
                    .putString(KEY_LOG, (kept + line).joinToString(ENTRY_SEPARATOR))
                    .apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not record widget diagnostics", e)
        }
    }

    /** Recorded steps, oldest first. */
    fun entries(context: Context): List<String> =
        prefs(context).getString(KEY_LOG, "").orEmpty()
            .split(ENTRY_SEPARATOR)
            .filter { it.isNotBlank() }

    fun clear(context: Context) = prefs(context).edit().remove(KEY_LOG).apply()

    /** An exception boiled down to something that fits in a bug report. */
    private fun describe(error: Throwable): String {
        val frames = error.stackTrace.take(8).joinToString("\n      ") { it.toString() }
        val cause = error.cause?.let { "\n    caused by ${it.javaClass.name}: ${it.message}" }.orEmpty()
        return "${error.javaClass.name}: ${error.message}\n      $frames$cause"
    }

    /**
     * Renders the widget's content the same way the launcher does — compose to
     * RemoteViews, then actually inflate them — and reports what happens.
     *
     * This is the part worth running: composing alone is not enough, because
     * the failure we are chasing shows up when the RemoteViews are inflated
     * and Glance's own layouts have to be found. Running it inside the app
     * means a release build tests its *own* shrunk resources.
     */
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    suspend fun selfTest(context: Context): String = try {
        val composed = GlanceRemoteViews()
            .compose(context, DpSize(180.dp, 110.dp)) {
                GlanceTheme { SproutWidgetUi(session = null, feed = null, babyName = null) }
            }
            .remoteViews
        withContext(Dispatchers.Main) { composed.apply(context, FrameLayout(context)) }
        "PASS — composed and inflated the widget successfully."
    } catch (e: Throwable) {
        "FAIL — ${describe(e)}"
    }

    /**
     * Which of Glance's own layouts are still present. If release builds are
     * stripping them, this is where it shows: the library declares hundreds,
     * and a shrunk build resolves far fewer. Best-effort — the R class it
     * reads is itself something R8 may have removed.
     */
    private fun glanceLayoutSurvey(context: Context): String = runCatching {
        val fields = Class.forName("androidx.glance.appwidget.R\$layout").fields
        var found = 0
        val missing = mutableListOf<String>()
        for (f in fields) {
            val id = f.getInt(null)
            if (runCatching { context.resources.getResourceName(id) }.isSuccess) {
                found++
            } else if (missing.size < 5) {
                missing.add(f.name)
            }
        }
        val total = fields.size
        buildString {
            append("$found/$total Glance layouts resolve")
            if (missing.isNotEmpty()) append(" — missing e.g. ${missing.joinToString(", ")}")
        }
    }.getOrElse { "could not survey (${it.javaClass.simpleName}) — R class likely shrunk away" }

    /**
     * How many widgets *Glance* can see, next to how many the launcher says are
     * placed. The two disagreeing is the tell-tale of a broken receiver-to-widget
     * mapping: the launcher happily shows a widget Glance no longer recognises,
     * so no session ever starts and provideGlance never runs.
     */
    private suspend fun glanceIdSurvey(context: Context): String = try {
        val ids = GlanceAppWidgetManager(context).getGlanceIds(SproutWidget::class.java)
        "${ids.size} known to Glance"
    } catch (e: Exception) {
        "lookup failed — ${e.javaClass.name}: ${e.message}"
    }

    /** The whole picture, as shareable plain text. */
    suspend fun report(context: Context): String {
        val app = context.applicationContext
        val pkg = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0)
        }.getOrNull()
        val placed = runCatching {
            AppWidgetManager.getInstance(app).getAppWidgetIds(
                ComponentName(app, SproutWidgetReceiver::class.java),
            ).size
        }.getOrElse { -1 }

        return buildString {
            appendLine("Sprout — widget diagnostics")
            appendLine("App: ${pkg?.versionName} (${pkg?.let { versionCodeOf(it) }})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Launcher instances placed: $placed")
            appendLine("Glance sees: ${glanceIdSurvey(context)}")
            // If R8 renamed this, the build is obfuscated — and anything Glance
            // reaches by name is worth suspecting.
            appendLine("Widget class: ${SproutWidget::class.java.name}")
            appendLine("Glance layouts: ${glanceLayoutSurvey(context)}")
            appendLine()
            appendLine("Self-test: ${selfTest(context)}")
            appendLine()
            appendLine("Recent widget activity:")
            val entries = entries(context)
            if (entries.isEmpty()) {
                appendLine("  (nothing recorded — the widget code never ran)")
            } else {
                entries.forEach { appendLine("  $it") }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
}
