package me.raddatz.cindy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colors the Material scheme has no slot for (iOS `Theme.swift` plus the system colors the
 * screens use for verdicts).
 */
@Immutable
data class CindyColors(
    /** Brand accent (iOS `AccentColor`): #B35500 in light mode (5.0:1 on white), #FF9F0A in dark (10:1 on black). */
    val brand: Color,
    /** Text and icons on [brand]: white on the dark light-mode orange, black on the bright dark-mode one. */
    val onBrand: Color,
    /** Good verdicts (subject detected, reserve, better score). */
    val success: Color,
    /** Bad verdicts and the last minute. */
    val danger: Color,
    /** The "take it easy" readiness band. */
    val warning: Color,
    /** Low threshold line in the debug sparkline. */
    val info: Color,
    /** iOS `panelBackground`: slightly raised panel on the screen background. */
    val panel: Color,
    /** The phone body in the stick-figure demos: opaque, it lies on top of the limbs. */
    val phoneBody: Color,
)

private val LightCindyColors = CindyColors(
    brand = Color(0xFFB35500),
    onBrand = Color.White,
    success = Color(0xFF248A3D),
    danger = Color(0xFFD70015),
    warning = Color(0xFFC93400),
    info = Color(0xFF0040DD),
    panel = Color(0xFFF2F2F7),
    phoneBody = Color(0xFF8E8E93),
)

private val DarkCindyColors = CindyColors(
    brand = Color(0xFFFF9F0A),
    onBrand = Color.Black,
    success = Color(0xFF30D158),
    danger = Color(0xFFFF453A),
    warning = Color(0xFFFFB340),
    info = Color(0xFF409CFF),
    panel = Color(0xFF1C1C1E),
    phoneBody = Color(0xFF8E8E93),
)

/** Built around the brand orange; neutral surfaces like the iOS system backgrounds, no dynamic color. */
private val LightScheme: ColorScheme = lightColorScheme(
    primary = LightCindyColors.brand,
    onPrimary = LightCindyColors.onBrand,
    primaryContainer = Color(0xFFFFDBC8),
    onPrimaryContainer = Color(0xFF331200),
    inversePrimary = Color(0xFFFFB68B),
    secondary = Color(0xFF765848),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC8),
    onSecondaryContainer = Color(0xFF2B160A),
    tertiary = Color(0xFF636032),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9E4AA),
    onTertiaryContainer = Color(0xFF1E1C00),
    error = LightCindyColors.danger,
    onError = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF2EDEA),
    onSurfaceVariant = Color(0xFF5F5E63),
    outline = Color(0xFF8A8589),
    outlineVariant = Color(0xFFD9D4D1),
    surfaceTint = LightCindyColors.brand,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE3E3E8),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFECECF1),
    surfaceContainerHighest = Color(0xFFE5E5EA),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = DarkCindyColors.brand,
    onPrimary = DarkCindyColors.onBrand,
    primaryContainer = Color(0xFF6B3A00),
    onPrimaryContainer = Color(0xFFFFDCC2),
    inversePrimary = Color(0xFFB35500),
    secondary = Color(0xFFE6BEAB),
    onSecondary = Color(0xFF432B1D),
    secondaryContainer = Color(0xFF4A3322),
    onSecondaryContainer = Color(0xFFFFDBC8),
    tertiary = Color(0xFFCDC890),
    onTertiary = Color(0xFF343108),
    tertiaryContainer = Color(0xFF4B481D),
    onTertiaryContainer = Color(0xFFE9E4AA),
    error = DarkCindyColors.danger,
    onError = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFF2F2F7),
    surface = Color.Black,
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFF3A3A3C),
    surfaceTint = DarkCindyColors.brand,
    surfaceBright = Color(0xFF2C2C2E),
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF121214),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF242426),
    surfaceContainerHighest = Color(0xFF2C2C2E),
)

private val LocalCindyColors = staticCompositionLocalOf { LightCindyColors }

/** Material 3 theme of the app. [darkTheme] follows the configuration, which the appearance setting drives. */
@Composable
fun CindyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCindyColors provides if (darkTheme) DarkCindyColors else LightCindyColors) {
        MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme, content = content)
    }
}

object CindyTheme {
    val colors: CindyColors
        @Composable @ReadOnlyComposable
        get() = LocalCindyColors.current
}
