package com.gproust.sprout

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gproust.sprout.ui.navigation.SproutApp
import com.gproust.sprout.ui.settings.AppLocale
import com.gproust.sprout.ui.theme.SproutTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent extra naming a bottom-bar route to open (used by the widget). */
        const val EXTRA_ROUTE = "com.gproust.sprout.extra.ROUTE"
    }

    /** Pending navigation request from the launch (or a re-delivered) intent. */
    private var routeRequest by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        routeRequest = intent.getStringExtra(EXTRA_ROUTE)
        setContent {
            SproutTheme {
                SproutApp(
                    routeRequest = routeRequest,
                    onRouteConsumed = { routeRequest = null },
                )
            }
        }
    }

    // The widget launches with CLEAR_TOP | SINGLE_TOP, so an already-running
    // activity receives the tap here instead of being recreated.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { routeRequest = it }
    }
}
