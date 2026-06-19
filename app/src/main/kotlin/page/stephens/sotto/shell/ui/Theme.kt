package page.stephens.sotto.shell.ui

// Sotto's brand color scheme, matched to the web app (sotto.stephens.page):
// deep navy (#002A4F favicon, #064b87 accent) with a teal secondary (#197).
// Deliberately a fixed brand palette, not Material You dynamic color - the
// navy is the identity, the same on every device. Light + dark track the system.

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF064B87),            // brand navy (web accent)
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FF),
    onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFF116B5B),          // teal (web #197 family)
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA6F2DE),
    onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFF3C5A78),           // muted slate-blue
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF001D36),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFBFCFE),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF42474E),
    outline = Color(0xFF73777F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA1C9FF),            // navy reads as light blue on dark
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF004A7C),
    onPrimaryContainer = Color(0xFFD3E3FF),
    secondary = Color(0xFF8AD6C2),
    onSecondary = Color(0xFF00382E),
    secondaryContainer = Color(0xFF005143),
    onSecondaryContainer = Color(0xFFA6F2DE),
    tertiary = Color(0xFFA5C8E9),
    onTertiary = Color(0xFF073353),
    tertiaryContainer = Color(0xFF24496B),
    onTertiaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF121416),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D9199),
)

@Composable
fun SottoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
