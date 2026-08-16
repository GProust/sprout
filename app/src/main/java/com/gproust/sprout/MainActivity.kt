package com.gproust.sprout

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.gproust.sprout.data.sync.SyncFiles
import com.gproust.sprout.data.sync.nearby.BluetoothNearbyTransport
import com.gproust.sprout.data.sync.nearby.NearbySettings
import com.gproust.sprout.data.sync.nearby.NearbySync
import com.gproust.sprout.ui.navigation.SproutApp
import com.gproust.sprout.ui.settings.AppLocale
import com.gproust.sprout.ui.theme.SproutTheme
import com.gproust.sprout.widget.updateSproutWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent extra naming a bottom-bar route to open (used by the widget). */
        const val EXTRA_ROUTE = "com.gproust.sprout.extra.ROUTE"
    }

    /** Pending navigation request from the launch (or a re-delivered) intent. */
    private var routeRequest by mutableStateOf<String?>(null)

    /**
     * A Sprout file another app asked us to open — an invitation or a replica.
     * Held here rather than acted on, because only the sync screen can tell the
     * two apart or do anything with either.
     */
    private var syncFile by mutableStateOf<Uri?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        routeRequest = intent.getStringExtra(EXTRA_ROUTE)
        syncFile = SyncFiles.incomingUri(intent)
        setContent {
            SproutTheme {
                SproutApp(
                    routeRequest = routeRequest,
                    onRouteConsumed = { routeRequest = null },
                    syncFile = syncFile,
                    onSyncFileConsumed = { syncFile = null },
                )
            }
        }
    }

    /**
     * Two things worth doing the moment the app comes back to the foreground.
     *
     * The widget's elapsed time is fixed when it is drawn, and the system only
     * refreshes widgets every half hour. Opening the app is both a moment the
     * widget is likely about to be looked at and a free chance to redraw it, so
     * take it. The other is looking for the household's phones.
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { updateSproutWidget(this@MainActivity) }
        openNearbyWindow()
    }

    /**
     * The automatic half of ADR-0010: one bounded discovery window when the app
     * comes to the foreground.
     *
     * Here rather than on the sync screen, because "when the app opens" is what
     * the ADR promises and nobody opens the sync screen in order to be synced.
     * Three things keep it modest: the switch has to be on, `NearbyPolicy`
     * throttles it to at most one window every few minutes, and the window
     * closes itself after ten seconds whether or not anyone answered. There is
     * no service and nothing running while the app is away.
     *
     * The outcome is deliberately silent. A merge shows up on its own as fresher
     * data, and "nobody was in the room" is not news worth a dialog — the sync
     * screen's *Sync now* is where an answer is expected.
     */
    private fun openNearbyWindow() {
        val app = application as? SproutApplication ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            // Spelled out rather than hidden behind a helper: lint only
            // recognises a direct SDK_INT check as a guard for the API-gated
            // transport, and only within the same lambda.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@launch
            if (!NearbySettings.isEnabled(this@MainActivity)) return@launch
            runCatching {
                NearbySync(
                    context = applicationContext,
                    engine = app.syncEngine,
                    pairingStore = app.pairingStore,
                    householdDevices = app.householdDevices,
                    transport = BluetoothNearbyTransport(applicationContext),
                    deviceName = { app.repository.parentProfile.first()?.name.orEmpty() },
                ).run(userAsked = false)
            }
        }
    }

    // The widget launches with CLEAR_TOP | SINGLE_TOP, so an already-running
    // activity receives the tap here instead of being recreated.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { routeRequest = it }
        SyncFiles.incomingUri(intent)?.let { syncFile = it }
    }
}
