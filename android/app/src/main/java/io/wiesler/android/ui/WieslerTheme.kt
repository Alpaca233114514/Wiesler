package io.wiesler.android.ui

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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val DarkSemanticColors = WieslerSemanticColors(
    download = Color(0xFF67E8C9),
    upload = Color(0xFF7BB8FF),
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFFC56D),
    danger = Color(0xFFFF7A85),
    graphCanvas = Color(0xFF0E1624),
    graphGrid = Color(0xFF23324A),
    graphNodeSurface = Color(0xFF172438),
    graphNodeSelected = Color(0xFF1B3B42),
    graphEdge = Color(0xFF486685)
)

private val LightSemanticColors = WieslerSemanticColors(
    download = Color(0xFF007965),
    upload = Color(0xFF1D62C8),
    success = Color(0xFF15803D),
    warning = Color(0xFFB45309),
    danger = Color(0xFFBE123C),
    graphCanvas = Color(0xFFF0F5F9),
    graphGrid = Color(0xFFC8D5E3),
    graphNodeSurface = Color(0xFFFFFFFF),
    graphNodeSelected = Color(0xFFD6F5EC),
    graphEdge = Color(0xFF7B95B0)
)

val LocalWieslerColors = staticCompositionLocalOf { DarkSemanticColors }

private val WieslerDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF67E8C9),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF0E4F42),
    onPrimaryContainer = Color(0xFFAEFBE6),
    secondary = Color(0xFF8CC4FF),
    onSecondary = Color(0xFF003258),
    secondaryContainer = Color(0xFF164778),
    onSecondaryContainer = Color(0xFFD3E7FF),
    tertiary = Color(0xFFFFC56D),
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF633F00),
    onTertiaryContainer = Color(0xFFFFE0B2),
    error = Color(0xFFFF8990),
    onError = Color(0xFF5F0A17),
    errorContainer = Color(0xFF851A2A),
    onErrorContainer = Color(0xFFFFDADE),
    background = Color(0xFF0A101B),
    onBackground = Color(0xFFE7EEF8),
    surface = Color(0xFF0D1523),
    onSurface = Color(0xFFE7EEF8),
    surfaceVariant = Color(0xFF182436),
    onSurfaceVariant = Color(0xFF9FB0C7),
    surfaceContainerLowest = Color(0xFF080D16),
    surfaceContainerLow = Color(0xFF111B2B),
    surfaceContainer = Color(0xFF152134),
    surfaceContainerHigh = Color(0xFF1C2B42),
    surfaceContainerHighest = Color(0xFF243652),
    outline = Color(0xFF3E526E),
    outlineVariant = Color(0xFF26354B)
)

private val WieslerLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF006B59),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA6F4DF),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF1A5FB4),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E6FF),
    onSecondaryContainer = Color(0xFF001C3B),
    tertiary = Color(0xFF8E5400),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB7),
    onTertiaryContainer = Color(0xFF2E1700),
    error = Color(0xFFBA1A2D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD9),
    onErrorContainer = Color(0xFF410008),
    background = Color(0xFFF5F8FC),
    onBackground = Color(0xFF111A26),
    surface = Color(0xFFF5F8FC),
    onSurface = Color(0xFF111A26),
    surfaceVariant = Color(0xFFE3ECF5),
    onSurfaceVariant = Color(0xFF435368),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF3F9),
    surfaceContainer = Color(0xFFE7EFF7),
    surfaceContainerHigh = Color(0xFFDFEAf5),
    surfaceContainerHighest = Color(0xFFD6E2F0),
    outline = Color(0xFF72839A),
    outlineVariant = Color(0xFFC1D0E0)
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

    CompositionLocalProvider(LocalWieslerColors provides semantic) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = ExpressiveShapes,
            typography = ExpressiveTypography,
            content = content
        )
    }
}
