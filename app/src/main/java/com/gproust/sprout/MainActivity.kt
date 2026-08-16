package com.gproust.sprout

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.gproust.sprout.data.sync.SyncFiles
import com.gproust.sprout.ui.navigation.SproutApp
import com.gproust.sprout.ui.settings.AppLocale
import com.gproust.sprout.ui.theme.SproutTheme
import com.gproust.sprout.widget.updateSproutWidget
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
     * The widget's elapsed time is fixed when it is drawn, and the system only
     * refreshes widgets every half hour. Opening the app is both a moment the
     * widget is likely about to be looked at and a free chance to redraw it, so
     * take it.
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { updateSproutWidget(this@MainActivity) }
    }

    // The widget launches with CLEAR_TOP | SINGLE_TOP, so an already-running
    // activity receives the tap here instead of being recreated.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { routeRequest = it }
        SyncFiles.incomingUri(intent)?.let { syncFile = it }
    }
}
