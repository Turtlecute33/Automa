package app.automa.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.automa.term.Look

/**
 * The terminal colours (Catppuccin Mocha, [Look]) for all screens, as in Varco, so that the app looks like one surface.
 * Always dark (DESIGN §5). Material 3 Expressive motion.
 */
@Composable
fun AutomaTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = AutomaColors,
        motionScheme = MotionScheme.expressive(),
        shapes = AutomaShapes,
        typography = AutomaTypography,
        content = content,
    )
}

/**
 * Material roles from the terminal colours (Varco `colorsFor`): surfaces are steps from background to foreground,
 * blue is the accent. All text roles have a contrast of 4.5:1 or more on the Mocha background.
 */
private val AutomaColors = run {
    val bg = Color(Look.BACKGROUND)
    val fg = Color(Look.FOREGROUND)
    val blue = Color(Look.ANSI[4])
    val pink = Color(Look.ANSI[5])
    val teal = Color(Look.ANSI[6])
    val red = Color(Look.ANSI[1])
    val on = Color(0xFF11111B) // Mocha "crust": text on the light accent colours.
    fun step(f: Float) = lerp(bg, fg, f)
    darkColorScheme(
        primary = blue,
        onPrimary = on,
        primaryContainer = lerp(bg, blue, 0.28f),
        onPrimaryContainer = fg,
        secondary = pink,
        onSecondary = on,
        secondaryContainer = lerp(bg, pink, 0.22f),
        onSecondaryContainer = fg,
        tertiary = teal,
        onTertiary = on,
        tertiaryContainer = lerp(bg, teal, 0.22f),
        onTertiaryContainer = fg,
        error = red,
        onError = on,
        errorContainer = lerp(bg, red, 0.25f),
        onErrorContainer = fg,
        background = bg,
        onBackground = fg,
        surface = bg,
        onSurface = fg,
        surfaceVariant = step(0.10f),
        onSurfaceVariant = step(0.70f),
        surfaceTint = blue,
        inverseSurface = fg,
        inverseOnSurface = bg,
        inversePrimary = lerp(blue, bg, 0.3f),
        outline = step(0.38f),
        outlineVariant = step(0.18f),
        scrim = Color.Black,
        surfaceBright = step(0.12f),
        surfaceDim = bg,
        surfaceContainerLowest = lerp(bg, Color.Black, 0.2f),
        surfaceContainerLow = step(0.03f),
        surfaceContainer = step(0.05f),
        surfaceContainerHigh = step(0.08f),
        surfaceContainerHighest = step(0.11f),
    )
}

private val AutomaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    largeIncreased = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
)

private val AutomaTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Medium),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Medium),
    )
}
