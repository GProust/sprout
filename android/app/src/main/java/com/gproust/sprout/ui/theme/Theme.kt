package com.gproust.sprout.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val SproutGreen = Color(0xFF4CAF7D)
private val SproutGreenDark = Color(0xFF1B5E3F)
private val SproutAmber = Color(0xFFF2A65A)
private val SproutBlush = Color(0xFFE8A0BF)

private val LightColors = lightColorScheme(
    primary = SproutGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F2E2),
    onPrimaryContainer = SproutGreenDark,
    secondary = SproutAmber,
    onSecondary = Color.White,
    tertiary = SproutBlush,
    onTertiary = Color.White,
    background = Color(0xFFF7FBF8),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD0A6),
    onPrimary = Color(0xFF00391F),
    primaryContainer = SproutGreenDark,
    onPrimaryContainer = Color(0xFFD6F2E2),
    secondary = SproutAmber,
    tertiary = SproutBlush,
)

private val SproutTypography = Typography()

@Composable
fun SproutTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SproutTypography,
        content = content,
    )
}
