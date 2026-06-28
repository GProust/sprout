package com.gproust.sprout

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gproust.sprout.ui.navigation.SproutApp
import com.gproust.sprout.ui.settings.AppLocale
import com.gproust.sprout.ui.theme.SproutTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SproutTheme {
                SproutApp()
            }
        }
    }
}
