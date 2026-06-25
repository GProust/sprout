package com.gproust.sprout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gproust.sprout.ui.navigation.SproutApp
import com.gproust.sprout.ui.theme.SproutTheme

class MainActivity : ComponentActivity() {
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
