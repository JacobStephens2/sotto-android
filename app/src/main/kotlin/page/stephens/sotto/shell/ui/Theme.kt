package page.stephens.sotto.shell.ui

// Sotto's design language, drawn from late-medieval / pre-Renaissance
// illuminated manuscripts - the monastic scriptorium where a text was
// transcribed and read aloud. Three pillars, all defined here:
//
//   COLOR   the Books-of-Hours triad: lapis-lazuli ultramarine (primary),
//           gold leaf (secondary), minium/vermilion "rubric" red (tertiary),
//           on vellum/parchment with iron-gall-ink text. Dark mode is the same
//           page by candlelight. A fixed brand palette, not Material You.
//   TYPE    EB Garamond (humanist serif) for display/headline/title - the
//           printed-book voice; MedievalSharp (legible medieval display) for
//           the Sotto wordmark only; default sans for body/labels because Sotto
//           is a reading app first. (MedievalSharp replaced UnifrakturMaguntia,
//           whose blackletter capital S read as a "g".)
//   SHAPE   squared corners - a manuscript page and its initial-boxes are
//           rectilinear, not the pill-rounded chrome of a phone app.
//
// Decorative components (the versal drop-cap, the fleuron rule, the wordmark)
// live in Illumination.kt. Font/style guide: docs/41-design-system.md.

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import page.stephens.sotto.R

// --- Parchment (light): vellum ground, ink text, lapis + gold + rubric ------
private val LightColors = lightColorScheme(
    primary = Color(0xFF2A3F8F),            // lapis-lazuli ultramarine
    onPrimary = Color(0xFFFBF3DD),          // parchment, not stark white
    primaryContainer = Color(0xFFD9E0F7),
    onPrimaryContainer = Color(0xFF0A1A4D),
    secondary = Color(0xFF8A6A12),          // gold leaf / ochre
    onSecondary = Color(0xFFFBF3DD),
    secondaryContainer = Color(0xFFF1DFA6),
    onSecondaryContainer = Color(0xFF2A1E00),
    tertiary = Color(0xFFA12C22),           // rubric vermilion (minium)
    onTertiary = Color(0xFFFBF3DD),
    tertiaryContainer = Color(0xFFF6D6CE),
    onTertiaryContainer = Color(0xFF3C0A05),
    error = Color(0xFF7A1208),              // deep crimson, kept distinct from rubric
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFADAD3),
    onErrorContainer = Color(0xFF2A0500),
    background = Color(0xFFF3E9CF),         // vellum
    onBackground = Color(0xFF2A2014),       // iron-gall ink
    surface = Color(0xFFF8EFD7),
    onSurface = Color(0xFF2A2014),
    surfaceVariant = Color(0xFFE6DABF),     // aged parchment
    onSurfaceVariant = Color(0xFF534935),   // faded ink
    outline = Color(0xFF887A5C),            // sepia rule
    outlineVariant = Color(0xFFD0C3A2),
)

// --- Candlelit scriptorium (dark): inked sepia ground, glowing gold + lapis -
private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC2F5),            // lapis lit by candle
    onPrimary = Color(0xFF0E1F52),
    primaryContainer = Color(0xFF24376F),
    onPrimaryContainer = Color(0xFFD9E0F7),
    secondary = Color(0xFFE6C66A),          // gold leaf catching the flame
    onSecondary = Color(0xFF3D2F00),
    secondaryContainer = Color(0xFF584400),
    onSecondaryContainer = Color(0xFFF1DFA6),
    tertiary = Color(0xFFF2B3A8),           // warm rubric
    onTertiary = Color(0xFF5C160C),
    tertiaryContainer = Color(0xFF7C2A20),
    onTertiaryContainer = Color(0xFFFFD9D2),
    error = Color(0xFFFFB4A8),
    onError = Color(0xFF690100),
    errorContainer = Color(0xFF931A0E),
    onErrorContainer = Color(0xFFFFDAD4),
    background = Color(0xFF1C1710),         // ink / dark sepia
    onBackground = Color(0xFFEBE0C8),       // parchment
    surface = Color(0xFF14100A),
    onSurface = Color(0xFFEBE0C8),
    surfaceVariant = Color(0xFF4C4534),
    onSurfaceVariant = Color(0xFFCFC2A2),
    outline = Color(0xFF988B6D),
    outlineVariant = Color(0xFF4C4534),
)

// --- Type -------------------------------------------------------------------
private val Garamond = FontFamily(Font(R.font.eb_garamond))
/** Medieval display face, for the Sotto wordmark only (see Illumination.kt). */
val Wordmark = FontFamily(Font(R.font.medieval_sharp))

private val base = Typography()
private val SottoTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = Garamond),
    displayMedium = base.displayMedium.copy(fontFamily = Garamond),
    displaySmall = base.displaySmall.copy(fontFamily = Garamond),
    headlineLarge = base.headlineLarge.copy(fontFamily = Garamond),
    headlineMedium = base.headlineMedium.copy(fontFamily = Garamond),
    headlineSmall = base.headlineSmall.copy(fontFamily = Garamond),
    titleLarge = base.titleLarge.copy(fontFamily = Garamond),
    titleMedium = base.titleMedium.copy(fontFamily = Garamond),
)

// --- Shape: squared, like a ruled page and its initial-boxes ----------------
private val SottoShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun SottoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SottoTypography,
        shapes = SottoShapes,
        content = content,
    )
}
