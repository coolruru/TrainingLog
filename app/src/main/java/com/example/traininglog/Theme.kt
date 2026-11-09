package com.example.traininglog

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("亮色"),
    DARK("暗色")
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF5F6368),
    onSecondary = Color.White,
    background = Color(0xFFF3F4F6),
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF1F3F4),
    onSurfaceVariant = Color(0xFF444746),
    outline = Color(0xFFD1D5DB),
    error = Color(0xFFB91C1C),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF1A3A6B),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFBDC1C6),
    onSecondary = Color(0xFF202124),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF1A1D23),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF252932),
    onSurfaceVariant = Color(0xFFC4C7C5),
    outline = Color(0xFF3C4043),
    error = Color(0xFFF28B82),
    onError = Color(0xFF3B0A09)
)

class ThemeStore(context: Context) {
    private val sp = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    fun get(): ThemeMode = runCatching {
        ThemeMode.valueOf(sp.getString("mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
    }.getOrDefault(ThemeMode.SYSTEM)

    fun set(mode: ThemeMode) {
        sp.edit().putString("mode", mode.name).apply()
    }
}

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (dark) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
