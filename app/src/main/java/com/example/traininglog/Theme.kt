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

// 运动风亮色模式：干净、硬朗、高对比
private val LightColors = lightColorScheme(
    primary = Color(0xFF12141A),          // 极深碳黑（作为亮色模式下的绝对主视觉，非常有力量感）
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E4E9),
    onPrimaryContainer = Color(0xFF12141A),
    secondary = Color(0xFFFF5C00),        // 运动能量橙（点睛之笔，用于触发动作或高亮）
    onSecondary = Color.White,
    background = Color(0xFFF4F5F7),       // 极浅冷灰，干净不刺眼
    onBackground = Color(0xFF12141A),
    surface = Color.White,
    onSurface = Color(0xFF12141A),
    surfaceVariant = Color(0xFFEAECEF),
    onSurfaceVariant = Color(0xFF4A4E58),
    outline = Color(0xFFD0D4DC),
    error = Color(0xFFFF3B30),
    onError = Color.White
)

// 运动风暗色模式：沉浸、极客、专业（核心）
private val DarkColors = darkColorScheme(
    primary = Color(0xFFC6FF00),          // 电光荧光绿/Volt（专业运动大厂标志性高对比色，极具冲击力）
    onPrimary = Color(0xFF0A0A0C),        // 亮色按钮上的文字用极黑，对比度拉满
    primaryContainer = Color(0xFF2C3800), // 深橄榄绿容器
    onPrimaryContainer = Color(0xFFE4FF80),
    secondary = Color(0xFF00E5FF),        // 赛博青（用于辅助计时、蓝线休息等次级状态）
    onSecondary = Color(0xFF00363D),
    background = Color(0xFF0A0A0C),       // 纯沉浸式极夜黑（OLED屏下完美融为一体）
    onBackground = Color(0xFFF4F4F5),     // 接近纯白的冷灰文字，长时间看眼睛不累
    surface = Color(0xFF14161B),          // 卡片表面：比背景稍亮一点点，拉开层次感
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF1E2128),   // 次级卡片或输入框背景
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF2E323D),          // 边缘线条：克制、不突兀
    error = Color(0xFFFF453A),            // 高饱和警示红
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
