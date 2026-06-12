package page.stephens.sotto.shell.ui

// The vertical-slice UI (21-vertical-slice-plan.md): share text in -> Confirm
// (derived, editable title; estimate; Narrate) -> Library list with live status.
// Material 3, single activity. Out of slice: rename/delete/voice picker/speed/
// sleep timer/queueing/About - they arrive in Phase 3.

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.engine.text.Titles
import page.stephens.sotto.shell.service.NarrationService
import java.io.File
import java.util.UUID

class SottoActivity : ComponentActivity() {

    private lateinit var db: SottoDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = SottoDatabase.open(this)
        val sharedIn = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain")
            intent.getStringExtra(Intent.EXTRA_TEXT) else null
        setContent {
            MaterialTheme {
                var shared by remember { mutableStateOf(sharedIn) }
                val text = shared
                if (text != null) ConfirmScreen(text, onDone = { shared = null })
                else LibraryScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConfirmScreen(text: String, onDone: () -> Unit) {
        var title by remember { mutableStateOf(Titles.derive(text)) }
        val words = remember(text) { text.trim().split(Regex("\\s+")).size }
        // Hardcoded device rate for the slice (~0.30 s/word measured by SPIKE-002);
        // per-device calibration is Phase 3 (the server's calibrated_rates, local).
        val estMin = (words * 0.30 / 60).coerceAtLeast(0.1)
        Scaffold(topBar = { TopAppBar(title = { Text("New oration") }) }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(120) },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("$words words · about ${"%.0f".format(estMin.coerceAtLeast(1.0))} min to narrate · voice af_heart",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { narrate(text, title.ifBlank { Titles.derive(text) }); onDone() },
                    modifier = Modifier.fillMaxWidth()) { Text("Narrate") }
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }

    private fun narrate(text: String, title: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            File(filesDir, "orations/$id").mkdirs()
            File(filesDir, "orations/$id/source.md").writeText(text)
            val now = System.currentTimeMillis()
            db.orations().insert(OrationEntity(
                id = id, title = title, status = "QUEUED", voiceId = "af_heart",
                words = text.trim().split(Regex("\\s+")).size, chunksTotal = null,
                createdAt = now, updatedAt = now))
            page.stephens.sotto.engine.store.JobLog(db.joblog()).log("created", title, id)
            startService(Intent(this@SottoActivity, NarrationService::class.java)
                .setAction(NarrationService.ACTION_NARRATE)
                .putExtra(NarrationService.EXTRA_ID, id))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LibraryScreen() {
        val items by db.orations().allFlow().collectAsState(initial = emptyList())
        Scaffold(topBar = {
            TopAppBar(title = { Text("Sotto") }, actions = {
                TextButton(onClick = {
                    startActivity(Intent(this@SottoActivity, page.stephens.sotto.spike.MainActivity::class.java))
                }) { Text("Lab") }
            })
        }) { pad ->
            if (items.isEmpty()) {
                Column(Modifier.padding(pad).padding(24.dp)) {
                    Text("Share text to Sotto from any app to hear it narrated.",
                        style = MaterialTheme.typography.bodyLarge)
                }
            } else LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(items, key = { it.id }) { o ->
                    OrationRow(o)
                    HorizontalDivider()
                }
            }
        }
    }

    @Composable
    private fun OrationRow(o: OrationEntity) {
        val subtitle = when (o.status) {
            "NARRATING" -> "Narrating · ${o.chunksDone} / ${o.chunksTotal ?: "?"} chunks"
            "QUEUED" -> "Queued" + if (o.chunksDone > 0) " · will resume from chunk ${o.chunksDone}" else ""
            "STOPPED" -> "Stopped at ${o.chunksDone} / ${o.chunksTotal ?: "?"} · tap to resume"
            "ERROR" -> "Failed: ${o.errorMessage ?: "unknown"} · tap to retry"
            else -> "${fmtLen(o.audioBytes)} · ${o.words} words"
        }
        ListItem(
            headlineContent = { Text(o.title) },
            supportingContent = { Text(subtitle) },
            modifier = Modifier.clickable {
                val action = when (o.status) {
                    "QUEUED", "STOPPED", "ERROR" -> NarrationService.ACTION_NARRATE  // (re)start; playback auto-follows
                    else -> NarrationService.ACTION_PLAY
                }
                startService(Intent(this@SottoActivity, NarrationService::class.java)
                    .setAction(action).putExtra(NarrationService.EXTRA_ID, o.id))
            },
        )
    }

    private fun fmtLen(bytes: Long): String {
        val secs = bytes * 8 / 64_000   // ADTS at 64 kbps
        return if (secs >= 3600) "%d h %d min".format(secs / 3600, (secs % 3600) / 60)
        else if (secs >= 60) "%d min".format(secs / 60) else "${secs}s"
    }
}
