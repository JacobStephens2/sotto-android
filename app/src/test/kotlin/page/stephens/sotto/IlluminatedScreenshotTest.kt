package page.stephens.sotto

// Roborazzi screenshot tests: render the real SottoTheme (illuminated palette,
// bundled EB Garamond + MedievalSharp fonts, squared shapes) and the real
// decorative components to PNG on the JVM via Robolectric - no emulator, no GPU
// (the point, on a headless CI box / this VM, where the emulator screencaps
// black through swiftshader).
//
// Uses Roborazzi's content-based captureRoboImage (no ComposeTestRule / host
// Activity, which Robolectric can't resolve here); the canvas is sized
// explicitly to a phone. These are faithful reconstructions of the screens
// using the actual theme and the real shell.ui components (SottoWordmark,
// Versal, FleuronRule) plus stock M3 with static sample data - enough to verify
// the *design system* renders. Not wired to Room/MediaController (those screens
// are private to the Activity); their value is visual, not behavioral.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import page.stephens.sotto.shell.ui.FleuronRule
import page.stephens.sotto.shell.ui.RubricHeader
import page.stephens.sotto.shell.ui.SottoTheme
import page.stephens.sotto.shell.ui.SottoWordmark
import page.stephens.sotto.shell.ui.Versal

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@OptIn(ExperimentalMaterial3Api::class)
class IlluminatedScreenshotTest {

    // A small comparison tolerance so cross-machine font anti-aliasing (local
    // goldens vs. the CI runner) doesn't flake the verify run; a real layout or
    // color change is far larger than 1% of pixels.
    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        captureRoboImage("screenshots/$name.png", roborazziOptions = options) {
            SottoTheme(darkTheme = dark) {
                Surface(Modifier.size(411.dp, 891.dp)) { content() }
            }
        }
    }

    @Test fun emptyLibrary_light() = shot("library-empty-light") { EmptyLibrary() }
    @Test fun emptyLibrary_dark() = shot("library-empty-dark", dark = true) { EmptyLibrary() }
    @Test fun library_light() = shot("library-list-light") { LibraryList() }
    @Test fun newOration_light() = shot("new-oration-light") { NewOration() }
    @Test fun newOration_dark() = shot("new-oration-dark", dark = true) { NewOration() }
    @Test fun player_light() = shot("player-light") { Player() }
    @Test fun player_dark() = shot("player-dark", dark = true) { Player() }

    // --- reconstructed screens ----------------------------------------------

    @Composable private fun Bar() = TopAppBar(title = { SottoWordmark() })

    @Composable private fun EmptyLibrary() {
        Scaffold(topBar = { Bar() }) { pad ->
            Column(Modifier.padding(pad).padding(24.dp)) {
                Spacer(Modifier.height(24.dp))
                Versal('S', Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
                Text("Share text to Sotto from any app, or tap Write to paste it here.",
                    style = MaterialTheme.typography.bodyLarge)
                FleuronRule()
                Text("Read to you in your own voice, in confidence.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable private fun LibraryList() {
        Scaffold(topBar = { Bar() }) { pad ->
            Column(Modifier.padding(pad)) {
                ListItem(headlineContent = { Text("Magnifica Humanitas") },
                    supportingContent = { Text("2 h 14 min · 19,284 words · synced") })
                HorizontalDivider()
                ListItem(headlineContent = { Text("Chapter Three") },
                    supportingContent = {
                        Column {
                            Text("Narrating · 4 / 9")
                            LinearProgressIndicator(progress = { 4f / 9f },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    })
                HorizontalDivider()
                ListItem(headlineContent = { Text("Career Advancement Plan") },
                    supportingContent = { Text("On your server · 28 min · tap to download") })
                HorizontalDivider()
            }
        }
    }

    @Composable private fun NewOration() {
        Scaffold(topBar = { TopAppBar(title = { Text("New oration") }) }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = "The evidence is real for each", onValueChange = {},
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("248 words · narrated on the server",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = true, onCheckedChange = {})
                    Spacer(Modifier.width(8.dp))
                    Text("Narrate on the server (OpenAI)", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                RubricHeader("Voice")
                listOf("Onyx · deep" to true, "Nova · warm" to false, "Shimmer · bright" to false)
                    .forEach { (label, chosen) ->
                        ListItem(headlineContent = { Text(label) }, leadingContent = {
                            FilterChip(selected = chosen, onClick = {},
                                label = { Text(if (chosen) "chosen" else "choose") })
                        })
                    }
                Spacer(Modifier.height(12.dp))
                Text("Narrating on server · 60%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { 0.6f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Narrate on server") }
            }
        }
    }

    @Composable private fun Player() {
        Scaffold(topBar = { TopAppBar(title = { Text("The evidence is real for each") }) }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
                Text("Narrating · chunk 3 / 8", style = MaterialTheme.typography.bodyMedium)
                Slider(value = 0.35f, onValueChange = {})
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = {}) { Text("« 15s") }
                    Button(onClick = {}) { Text("Play") }
                    Button(onClick = {}) { Text("15s »") }
                }
                Spacer(Modifier.height(12.dp))
                RubricHeader("Speed")
                Row {
                    listOf("0.8x" to false, "1.0x" to true, "1.25x" to false, "1.5x" to false).forEach { (s, on) ->
                        FilterChip(selected = on, onClick = {}, label = { Text(s) },
                            modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {}) { Text("Stop narrating (keeps what's done)") }
                FleuronRule()
                RubricHeader("Text")
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                        fontSize = 34.sp, color = MaterialTheme.colorScheme.tertiary)) { append("Y") }
                    append("es - and the evidence in your own stack genuinely backs all three. " +
                        "But I'd be careful how you present them, because three co-equal titles " +
                        "can read as unfocused, and your target rewards a clear lead.")
                }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
