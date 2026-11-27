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
import androidx.compose.runtime.LaunchedEffect
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

// 对齐 desktop/ui/theme.css 与 WCAG AA 对比度标准：纯中性碳黑/素白灰阶 + 克制语义色。
// Mirrors desktop/ui/theme.css with WCAG AA contrast: pure carbon/porcelain neutrals + restrained semantic colors.
private val DarkSemanticColors = WieslerSemanticColors(
    download = Color(0xFF6BA4CF),
    upload = Color(0xFFA8A8B0),
    success = Color(0xFF56B886),
    warning = Color(0xFFE0A458),
    danger = Color(0xFFE57373),
    graphCanvas = Color(0xFF111113),
    graphGrid = Color(0xFF26262A),
    graphNodeSurface = Color(0xFF1A1A1D),
    graphNodeSelected = Color(0xFF1B2B38),
    graphEdge = Color(0xFF45454B)
)

private val LightSemanticColors = WieslerSemanticColors(
    download = Color(0xFF226BA5),
    upload = Color(0xFF55555C),
    success = Color(0xFF187A4E),
    warning = Color(0xFF9E5D12),
    danger = Color(0xFFC43434),
    graphCanvas = Color(0xFFF6F6F8),
    graphGrid = Color(0xFFDADAE0),
    graphNodeSurface = Color(0xFFFFFFFF),
    graphNodeSelected = Color(0xFFE8F2FA),
    graphEdge = Color(0xFF9CA3AF)
)

val LocalWieslerColors = staticCompositionLocalOf { DarkSemanticColors }
val LocalIsDarkTheme = staticCompositionLocalOf { true }
val LocalTopBarClearance = staticCompositionLocalOf { 0.dp }
val LocalBottomBarClearance = staticCompositionLocalOf { 0.dp }

private val WieslerDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF6BA4CF),
    onPrimary = Color(0xFF0C1217),
    primaryContainer = Color(0xFF1B2A36),
    onPrimaryContainer = Color(0xFFCAE1F2),
    secondary = Color(0xFFD8D8DC),
    onSecondary = Color(0xFF121214),
    secondaryContainer = Color(0xFF222226),
    onSecondaryContainer = Color(0xFFECECEF),
    tertiary = Color(0xFF9A9AA0),
    onTertiary = Color(0xFF121214),
    tertiaryContainer = Color(0xFF242428),
    onTertiaryContainer = Color(0xFFE0E0E4),
    error = Color(0xFFE57373),
    onError = Color(0xFF240909),
    errorContainer = Color(0xFF3A1B1B),
    onErrorContainer = Color(0xFFFFD8D8),
    background = Color(0xFF0C0C0D),
    onBackground = Color(0xFFF2F2F4),
    surface = Color(0xFF0C0C0D),
    onSurface = Color(0xFFF2F2F4),
    surfaceVariant = Color(0xFF1C1C1F),
    onSurfaceVariant = Color(0xFF94949B),
    surfaceContainerLowest = Color(0xFF111113),
    surfaceContainerLow = Color(0xFF151517),
    surfaceContainer = Color(0xFF18181B),
    surfaceContainerHigh = Color(0xFF202024),
    surfaceContainerHighest = Color(0xFF2C2C32),
    outline = Color(0xFF44444C),
    outlineVariant = Color(0xFF242429)
)

private val WieslerLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF226BA5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4F0F9),
    onPrimaryContainer = Color(0xFF10324D),
    secondary = Color(0xFF222226),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFECECF0),
    onSecondaryContainer = Color(0xFF161618),
    tertiary = Color(0xFF55555C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFECECF0),
    onTertiaryContainer = Color(0xFF222226),
    error = Color(0xFFC43434),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE8E8),
    onErrorContainer = Color(0xFF5C1515),
    background = Color(0xFFF4F4F6),
    onBackground = Color(0xFF141416),
    surface = Color(0xFFF4F4F6),
    onSurface = Color(0xFF141416),
    surfaceVariant = Color(0xFFECECF0),
    onSurfaceVariant = Color(0xFF63636A),
    surfaceContainerLowest = Color(0xFFF6F6F8),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1F1F4),
    surfaceContainerHighest = Color(0xFFE4E4EA),
    outline = Color(0xFFB4B4BE),
    outlineVariant = Color(0xFFE6E6EC)
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

// 严格收敛字阶：主体仅保留 17sp（区块标题）、14.5sp（正文/子标题）、13sp（辅助元数据）三档，无小于 13sp 文本。
// Strict 3-step body type scale (17sp / 14.5sp / 13sp) with zero <13sp text; display/headline reserved for page & hero.
private val ExpressiveTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.15).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.5.sp,
        lineHeight = 21.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.5.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 21.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.5.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
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
        LaunchedEffect(view, darkTheme) {
            // 仅在深浅色主题切换时同步系统栏图标前景色，避免每次实时数据重组触发 WindowInsetsController IPC。
            // Sync system bar icon contrast only when darkTheme changes instead of every recomposition.
            val window = view.context.findActivity()?.window ?: return@LaunchedEffect
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
