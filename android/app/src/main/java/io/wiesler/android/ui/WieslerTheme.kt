package io.wiesler.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.wiesler.android.ThemePreference

data class WieslerSemanticColors(
    val download: Color,
    val upload: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val graphCanvas: Color,
    val graphGrid: Color,
    val graphNodeSurface: Color,
    val graphNodeSelected: Color,
    val graphEdge: Color
)

// 对齐 desktop/ui/theme.css：纯黑白灰中性底色 + 单一桌面端静谧蓝辅助色 (#6BA4CF / #2B7AB8)。
// Mirrors desktop/ui/theme.css: pure achromatic black/white neutrals + single desktop steel-blue accent.
private val DarkSemanticColors = WieslerSemanticColors(
    download = Color(0xFF6BA4CF),
    upload = Color(0xFFB5B5B5),
    success = Color(0xFF6BA4CF),
    warning = Color(0xFFD99B4E),
    danger = Color(0xFFDF6C6C),
    graphCanvas = Color(0xFF131313),
    graphGrid = Color(0xFF282828),
    graphNodeSurface = Color(0xFF1C1C1C),
    graphNodeSelected = Color(0xFF1C2A35),
    graphEdge = Color(0xFF4A4A4A)
)

private val LightSemanticColors = WieslerSemanticColors(
    download = Color(0xFF2B7AB8),
    upload = Color(0xFF525252),
    success = Color(0xFF2B7AB8),
    warning = Color(0xFFAD681B),
    danger = Color(0xFFC93B3B),
    graphCanvas = Color(0xFFF5F5F5),
    graphGrid = Color(0xFFDCDCDC),
    graphNodeSurface = Color(0xFFFFFFFF),
    graphNodeSelected = Color(0xFFE7F1F9),
    graphEdge = Color(0xFF9CA3AF)
)

val LocalWieslerColors = staticCompositionLocalOf { DarkSemanticColors }
val LocalIsDarkTheme = staticCompositionLocalOf { true }
val LocalBottomBarClearance = staticCompositionLocalOf { 0.dp }

private val WieslerDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF6BA4CF),
    onPrimary = Color(0xFF0E1419),
    primaryContainer = Color(0xFF1C2D3B),
    onPrimaryContainer = Color(0xFFCAE1F2),
    secondary = Color(0xFFD4D4D4),
    onSecondary = Color(0xFF141414),
    secondaryContainer = Color(0xFF242424),
    onSecondaryContainer = Color(0xFFECECEC),
    tertiary = Color(0xFF9E9E9E),
    onTertiary = Color(0xFF141414),
    tertiaryContainer = Color(0xFF262626),
    onTertiaryContainer = Color(0xFFE0E0E0),
    error = Color(0xFFDF6C6C),
    onError = Color(0xFF240909),
    errorContainer = Color(0xFF3A1B1B),
    onErrorContainer = Color(0xFFFFD8D8),
    background = Color(0xFF101010),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFF8C8C8C),
    surfaceContainerLowest = Color(0xFF131313),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1B1B1B),
    surfaceContainerHigh = Color(0xFF222222),
    surfaceContainerHighest = Color(0xFF2B2B2B),
    outline = Color(0xFF383838),
    outlineVariant = Color(0xFF262626)
)

private val WieslerLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF2B7AB8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2EFF8),
    onPrimaryContainer = Color(0xFF10324D),
    secondary = Color(0xFF262626),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBEBEB),
    onSecondaryContainer = Color(0xFF171717),
    tertiary = Color(0xFF595959),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFECECEC),
    onTertiaryContainer = Color(0xFF262626),
    error = Color(0xFFC93B3B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE8E8),
    onErrorContainer = Color(0xFF5C1515),
    background = Color(0xFFF3F3F3),
    onBackground = Color(0xFF171717),
    surface = Color(0xFFF3F3F3),
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFECECEC),
    onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceContainerLowest = Color(0xFFF6F6F6),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F0F0),
    surfaceContainerHighest = Color(0xFFE5E5E5),
    outline = Color(0xFFC8C8C8),
    outlineVariant = Color(0xFFE4E4E4)
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val ExpressiveTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WieslerExpressiveTheme(
    themePreference: ThemePreference,
    dynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> WieslerDarkColorScheme
        else -> WieslerLightColorScheme
    }
    val semantic = if (darkTheme) DarkSemanticColors else LightSemanticColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // 同步系统状态栏与导航栏图标前景色，避免系统浅色模式叠加 App 深色主题时图标黑底黑字。
            // Sync system bar icon contrast with the app's active dark/light theme.
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalWieslerColors provides semantic,
        LocalIsDarkTheme provides darkTheme
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = ExpressiveShapes,
            typography = ExpressiveTypography,
            content = content
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
