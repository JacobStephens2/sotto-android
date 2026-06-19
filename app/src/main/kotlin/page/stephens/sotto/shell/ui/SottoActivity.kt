package page.stephens.sotto.shell.ui

// Phase 3 surface (docs/03-roadmap.md): Library with detail actions (rename,
// delete, source text, export via SAF), a Player screen over a MediaController
// (speed, sleep timer, 15s skips, catching-up state), voice picker with bundled
// previews, calibrated narration estimate, and About (provenance + job log).
// Stock Material 3 throughout (41-design-system.md).

import android.content.ComponentName
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import page.stephens.sotto.engine.store.JobLog
import page.stephens.sotto.engine.store.JobLogEntity
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.engine.text.Titles
import page.stephens.sotto.shell.service.NarrationService
import java.io.File
import java.util.UUID

private val VOICES = listOf(
    "af_heart" to "Heart · US female",
    "af_bella" to "Bella · US female",
    "af_nicole" to "Nicole · US female",
    "am_adam" to "Adam · US male",
    "am_michael" to "Michael · US male",
    "bf_emma" to "Emma · UK female",
    "bm_george" to "George · UK male",
)

// Display labels for the server-side OpenAI voices (offered only to allowed
// accounts; the names come from /api/v1/me, these just prettify them).
private val OPENAI_VOICE_LABELS = mapOf(
    "onyx" to "Onyx · deep", "alloy" to "Alloy · neutral", "nova" to "Nova · warm",
    "shimmer" to "Shimmer · bright", "sage" to "Sage · calm", "fable" to "Fable · expressive",
    "echo" to "Echo · crisp",
)

private sealed class Screen {
    data object Library : Screen()
    data object Compose : Screen()
    data class Confirm(val text: String) : Screen()
    data class Play(val id: String) : Screen()
    data object About : Screen()
    data object Account : Screen()
}

class SottoActivity : ComponentActivity() {

    private lateinit var db: SottoDatabase
    private val sync by lazy { page.stephens.sotto.shell.sync.Sync.provider(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = SottoDatabase.open(this)
        val sharedIn = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain")
            intent.getStringExtra(Intent.EXTRA_TEXT) else null
        setContent {
            SottoTheme {
                var screen by remember {
                    mutableStateOf<Screen>(if (sharedIn != null) Screen.Confirm(sharedIn) else Screen.Library)
                }
                BackHandler(enabled = screen != Screen.Library) { screen = Screen.Library }
                when (val s = screen) {
                    is Screen.Library -> LibraryScreen(
                        onOpen = { o ->
                            if (!o.downloaded) downloadFromServer(o)
                            else { openOration(o); screen = Screen.Play(o.id) }
                        },
                        onAbout = { screen = Screen.About },
                        onCompose = { screen = Screen.Compose },
                        onAccount = if (sync != null) ({ screen = Screen.Account }) else null)
                    is Screen.Compose -> ComposeScreen(
                        onContinue = { text -> screen = Screen.Confirm(text) },
                        onBack = { screen = Screen.Library })
                    is Screen.Confirm -> ConfirmScreen(s.text,
                        onDone = { screen = Screen.Library })
                    is Screen.Play -> PlayerScreen(s.id, onBack = { screen = Screen.Library })
                    is Screen.About -> AboutScreen(onBack = { screen = Screen.Library })
                    is Screen.Account -> AccountScreen(onBack = { screen = Screen.Library })
                }
            }
        }
    }

    private fun svc(action: String, id: String? = null, minutes: Int? = null) =
        startService(Intent(this, NarrationService::class.java).setAction(action).apply {
            id?.let { putExtra(NarrationService.EXTRA_ID, it) }
            minutes?.let { putExtra(NarrationService.EXTRA_MINUTES, it) }
        })

    private fun openOration(o: OrationEntity) = when (o.status) {
        "QUEUED", "STOPPED", "ERROR" -> svc(NarrationService.ACTION_NARRATE, o.id)
        else -> svc(NarrationService.ACTION_PLAY, o.id)
    }

    // ---------------------------------------------------------------- Confirm

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConfirmScreen(text: String, onDone: () -> Unit) {
        var title by remember { mutableStateOf(Titles.derive(text)) }
        var voice by remember { mutableStateOf("af_heart") }
        val words = remember(text) { text.trim().split(Regex("\\s+")).size }
        // Calibrated rate: median of this device's completed orations, else the
        // SPIKE-002 constant (the server's calibrated_rates logic, local).
        val rate by produceState(0.30) {
            value = withContext(Dispatchers.IO) {
                val r = db.orations().completedRates().sorted()
                if (r.isEmpty()) 0.30 else r[r.size / 2]
            }
        }
        val estMin = (words * rate / 60).coerceAtLeast(1.0)
        // Server narration is offered only when connected to an account the server
        // lets onto the paid OpenAI backend (docs/12-api-contract.md, /api/v1/me).
        val server by produceState<page.stephens.sotto.shell.sync.ServerAccount?>(null) {
            value = sync?.account()?.getOrNull()
        }
        var onServer by remember { mutableStateOf(false) }
        var serverPct by remember { mutableStateOf<Int?>(null) }   // non-null while a server job runs
        var serverErr by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val busy = serverPct != null
        val openaiVoices = server?.openaiVoices ?: emptyList()

        Scaffold(topBar = { TopAppBar(title = { Text("New oration") }) }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(120) },
                    label = { Text("Name") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("$words words · " +
                        if (onServer) "narrated on the server" else "about ${"%.0f".format(estMin)} min to narrate",
                    style = MaterialTheme.typography.bodyMedium)

                if (server?.mayOpenai == true && openaiVoices.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = onServer, enabled = !busy, onCheckedChange = {
                            onServer = it
                            voice = if (it) (openaiVoices.firstOrNull() ?: "onyx") else "af_heart"
                        })
                        Spacer(Modifier.width(8.dp))
                        Text("Narrate on the server (OpenAI)", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (onServer) Text(
                        "Uses your account's OpenAI credits; it appears in your library when ready.",
                        style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(12.dp))
                Text("Voice", style = MaterialTheme.typography.labelLarge)
                val choices = if (onServer)
                    openaiVoices.map { it to (OPENAI_VOICE_LABELS[it] ?: it.replaceFirstChar(Char::uppercase)) }
                else VOICES
                choices.forEach { (id, label) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = {
                            FilterChip(selected = voice == id, enabled = !busy, onClick = { voice = id },
                                label = { Text(if (voice == id) "chosen" else "choose") })
                        },
                        trailingContent = (@Composable {
                            TextButton(onClick = { playPreview(id) }) { Text("Hear") }
                        }).takeIf { !onServer },
                    )
                }

                Spacer(Modifier.height(12.dp))
                if (busy) {
                    Text("Narrating on server · ${serverPct}%", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { (serverPct ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Spacer(Modifier.height(12.dp))
                }
                serverErr?.let {
                    Text("Server narration failed: $it", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }

                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    val finalTitle = title.ifBlank { Titles.derive(text) }
                    if (!onServer) { narrate(text, finalTitle, voice); onDone(); return@Button }
                    val s = sync ?: return@Button
                    scope.launch {
                        serverErr = null; serverPct = 0
                        val jobId = s.narrateOnServer(text, voice, finalTitle).getOrElse {
                            serverErr = it.message ?: "could not submit"; serverPct = null; return@launch
                        }
                        while (true) {
                            kotlinx.coroutines.delay(2500)
                            val st = s.serverJobStatus(jobId).getOrNull() ?: continue
                            serverPct = st.pct
                            if (st.error != null) { serverErr = st.error; serverPct = null; break }
                            if (st.status == "done" && st.name != null) {
                                s.syncNow(); serverPct = null; onDone(); break
                            }
                        }
                    }
                }) { Text(if (onServer) "Narrate on server" else "Narrate") }
                TextButton(onClick = onDone, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }

    private var preview: MediaPlayer? = null
    private fun playPreview(voiceId: String) {
        preview?.release()
        preview = MediaPlayer().apply {
            assets.openFd("previews/$voiceId.mp3").use { fd ->
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            prepare(); start()
        }
    }

    private fun narrate(text: String, title: String, voice: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            File(filesDir, "orations/$id").mkdirs()
            File(filesDir, "orations/$id/source.md").writeText(text)
            val now = System.currentTimeMillis()
            db.orations().insert(OrationEntity(
                id = id, title = title, status = "QUEUED", voiceId = voice,
                words = text.trim().split(Regex("\\s+")).size, chunksTotal = null,
                createdAt = now, updatedAt = now))
            JobLog(db.joblog()).log("created", title, id, "voice=$voice")
            svc(NarrationService.ACTION_NARRATE, id)
        }
    }

    // ---------------------------------------------------------------- Library

    // ---------------------------------------------------------------- Compose

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ComposeScreen(onContinue: (String) -> Unit, onBack: () -> Unit) {
        var text by remember { mutableStateOf("") }
        val words = remember(text) {
            text.trim().let { if (it.isEmpty()) 0 else it.split(Regex("\\s+")).size }
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text("New oration") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
        }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Paste or type what Sotto should read") },
                    placeholder = { Text("# Markdown welcome\n\nHeadings get pauses; links and tables are read for the ear.") },
                    modifier = Modifier.fillMaxWidth().weight(1f))
                Spacer(Modifier.height(8.dp))
                Text(if (words == 0) "Nothing yet" else "$words words",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onContinue(text) }, enabled = words > 0,
                    modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            }
        }
    }

    // ---------------------------------------------------------------- Library

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun LibraryScreen(onOpen: (OrationEntity) -> Unit, onAbout: () -> Unit,
                              onCompose: () -> Unit, onAccount: (() -> Unit)?) {
        val items by db.orations().allFlow().collectAsState(initial = emptyList())
        val downloads by page.stephens.sotto.shell.sync.DownloadProgress.state.collectAsState()
        var sheetFor by remember { mutableStateOf<OrationEntity?>(null) }
        Scaffold(topBar = {
            TopAppBar(title = { SottoWordmark() }, actions = {
                if (onAccount != null) TextButton(onClick = onAccount) { Text("Account") }
                TextButton(onClick = onAbout) { Text("About") }
                TextButton(onClick = {
                    startActivity(Intent(this@SottoActivity, page.stephens.sotto.spike.MainActivity::class.java))
                }) { Text("Lab") }
            })
        }, floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(onClick = onCompose) { Text("+  Write") }
        }) { pad ->
            if (items.isEmpty()) Column(Modifier.padding(pad).padding(24.dp)) {
                Spacer(Modifier.height(24.dp))
                Versal('S', Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
                Text("Share text to Sotto from any app, or tap Write to paste it here.",
                    style = MaterialTheme.typography.bodyLarge)
                FleuronRule()
                Text("Read to you in your own voice, in confidence.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(items, key = { it.id }) { o ->
                    ListItem(
                        headlineContent = { Text(o.title) },
                        supportingContent = {
                            val dlPct = downloads[o.id]
                            Column {
                                Text(if (dlPct != null) "Downloading · $dlPct%" else subtitleOf(o))
                                if (dlPct != null)
                                    LinearProgressIndicator(
                                        progress = { dlPct / 100f },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                else if (o.status == "NARRATING" && (o.chunksTotal ?: 0) > 0)
                                    LinearProgressIndicator(
                                        progress = { o.chunksDone.toFloat() / o.chunksTotal!! },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = { onOpen(o) },
                            onLongClick = { sheetFor = o }),
                    )
                    HorizontalDivider()
                }
            }
        }
        sheetFor?.let { o -> OrationSheet(o, onDismiss = { sheetFor = null }) }
    }

    private fun subtitleOf(o: OrationEntity) = if (!o.downloaded)
        "On your server · ${fmtSecs(o.audioDurationMs / 1000)} · tap to download"
    else when (o.status) {
        "NARRATING" -> "Narrating · ${o.chunksDone} / ${o.chunksTotal ?: "?"}"
        "QUEUED" -> "Queued" + if (o.chunksDone > 0) " · resumes from chunk ${o.chunksDone}" else ""
        "STOPPED" -> "Stopped at ${o.chunksDone} / ${o.chunksTotal ?: "?"} · tap to resume"
        "ERROR" -> "Failed: ${o.errorMessage ?: "unknown"} · tap to retry"
        else -> "${fmtSecs(if (o.audioDurationMs > 0) o.audioDurationMs / 1000 else o.audioBytes * 8 / 64_000)} · ${o.words} words" +
            (if (o.serverName != null) " · synced" else "")
    }

    private fun downloadFromServer(o: OrationEntity) {
        if (page.stephens.sotto.shell.sync.DownloadProgress.isActive(o.id)) return
        lifecycleScope.launch {
            sync?.download(o)?.onFailure { t ->
                lifecycleScope.launch(Dispatchers.IO) {
                    JobLog(db.joblog()).log("error", o.title, o.id, "download: $t")
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun OrationSheet(o: OrationEntity, onDismiss: () -> Unit) {
        var renaming by remember { mutableStateOf(false) }
        var viewingSource by remember { mutableStateOf(false) }
        val export = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("audio/aac")) { uri ->
            if (uri != null) lifecycleScope.launch(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    File(filesDir, "orations/${o.id}/audio.aac").inputStream().use { it.copyTo(out) }
                }
                JobLog(db.joblog()).log("exported", o.title, o.id)
            }
            onDismiss()
        }
        ModalBottomSheet(onDismissRequest = onDismiss) {
            ListItem(headlineContent = { Text("Rename") },
                modifier = Modifier.clickable { renaming = true })
            ListItem(headlineContent = { Text("View source text") },
                modifier = Modifier.clickable { viewingSource = true })
            if (sync?.accountEmail() != null && o.serverName == null && o.downloaded &&
                o.status in listOf("COMPLETE", "STOPPED")) {
                ListItem(headlineContent = { Text("Upload to account") },
                    modifier = Modifier.clickable {
                        lifecycleScope.launch { sync?.upload(o) }
                        onDismiss()
                    })
            }
            ListItem(headlineContent = { Text("Export audio") },
                modifier = Modifier.clickable {
                    export.launch(o.title.replace(Regex("[^A-Za-z0-9 _-]"), "").take(60) + ".aac")
                })
            ListItem(headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable {
                    svc(NarrationService.ACTION_DELETE, o.id); onDismiss()
                })
            Spacer(Modifier.height(24.dp))
        }
        if (renaming) {
            var name by remember { mutableStateOf(o.title) }
            AlertDialog(onDismissRequest = { renaming = false; onDismiss() },
                title = { Text("Rename") },
                text = { OutlinedTextField(value = name, onValueChange = { name = it.take(120) }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            db.orations().rename(o.id, name.trim().ifBlank { o.title }, System.currentTimeMillis())
                            JobLog(db.joblog()).log("renamed", name, o.id)
                        }
                        renaming = false; onDismiss()
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { renaming = false; onDismiss() }) { Text("Cancel") } })
        }
        if (viewingSource) {
            val src by produceState("Loading...") {
                value = withContext(Dispatchers.IO) {
                    runCatching { File(filesDir, "orations/${o.id}/source.md").readText() }
                        .getOrDefault("(source text unavailable)")
                }
            }
            AlertDialog(onDismissRequest = { viewingSource = false; onDismiss() },
                title = { Text(o.title) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(src, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { viewingSource = false; onDismiss() }) { Text("Close") } })
        }
    }

    // ----------------------------------------------------------------- Player

    @Composable
    private fun rememberController(): MediaController? {
        var controller by remember { mutableStateOf<MediaController?>(null) }
        DisposableEffect(Unit) {
            val future = MediaController.Builder(this@SottoActivity,
                SessionToken(this@SottoActivity, ComponentName(this@SottoActivity, NarrationService::class.java)))
                .buildAsync()
            future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(this@SottoActivity))
            onDispose { MediaController.releaseFuture(future) }
        }
        return controller
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PlayerScreen(id: String, onBack: () -> Unit) {
        val row by db.orations().byIdFlow(id).collectAsState(initial = null)
        val controller = rememberController()
        var positionMs by remember { mutableStateOf(0L) }
        var durationMs by remember { mutableStateOf(0L) }
        var playing by remember { mutableStateOf(false) }
        var ended by remember { mutableStateOf(false) }
        var speed by remember { mutableStateOf(1.0f) }
        var sleepLeft by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(controller) {
            while (true) {
                controller?.let {
                    if (it.currentMediaItem?.mediaId == id) {
                        positionMs = it.currentPosition
                        durationMs = it.duration.coerceAtLeast(0)
                        playing = it.isPlaying
                        ended = it.playbackState == Player.STATE_ENDED
                        speed = it.playbackParameters.speed
                    }
                }
                kotlinx.coroutines.delay(500)
            }
        }
        val o = row ?: return
        val catchingUp = ended && o.status == "NARRATING"
        Scaffold(topBar = {
            TopAppBar(title = { Text(o.title, maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
        }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp)) {
                Text(when {
                    catchingUp -> "Catching up - the narrator is just behind you..."
                    o.status == "NARRATING" -> "Narrating · chunk ${o.chunksDone} / ${o.chunksTotal ?: "?"}"
                    o.status == "STOPPED" -> "Stopped at chunk ${o.chunksDone} / ${o.chunksTotal ?: "?"}"
                    else -> "${o.words} words"
                }, style = MaterialTheme.typography.bodyMedium)
                if (o.status == "NARRATING" && (o.chunksTotal ?: 0) > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { o.chunksDone.toFloat() / o.chunksTotal!! },
                        modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(20.dp))
                Slider(
                    value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onValueChange = { f -> controller?.seekTo((f * durationMs).toLong()) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtSecs(positionMs / 1000)); Text(fmtSecs(durationMs / 1000))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = { controller?.seekTo((positionMs - 15_000).coerceAtLeast(0)) }) { Text("« 15s") }
                    Button(onClick = {
                        controller?.let { c ->
                            if (c.currentMediaItem?.mediaId != id) svc(NarrationService.ACTION_PLAY, id)
                            else if (playing) c.pause() else c.play()
                        } ?: svc(NarrationService.ACTION_PLAY, id)
                    }) { Text(if (playing) "Pause" else "Play") }
                    Button(onClick = { controller?.seekTo(positionMs + 15_000) }) { Text("15s »") }
                }
                Spacer(Modifier.height(20.dp))
                Text("Speed", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.8f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { s ->
                        FilterChip(selected = speed == s,
                            onClick = { controller?.setPlaybackSpeed(s) },
                            label = { Text("${s}x") })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Sleep timer", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { m ->
                        FilterChip(selected = sleepLeft == m,
                            onClick = {
                                val next = if (sleepLeft == m) 0 else m
                                sleepLeft = if (next == 0) null else next
                                svc(NarrationService.ACTION_SLEEP_TIMER, minutes = next)
                            },
                            label = { Text("$m min") })
                    }
                }
                if (o.status == "NARRATING") {
                    Spacer(Modifier.height(20.dp))
                    TextButton(onClick = { svc(NarrationService.ACTION_STOP_NARRATION) }) {
                        Text("Stop narrating (keeps what's done)")
                    }
                }
                // Read-along: the source is written in full at creation, so the
                // whole text is readable here even while it's still synthesizing.
                val source by produceState("", id, o.downloaded) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { File(filesDir, "orations/$id/source.md").readText() }.getOrDefault("")
                    }
                }
                if (source.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Text("Text", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    Text(source, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
            }
        }
    }

    // ---------------------------------------------------------------- Account

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AccountScreen(onBack: () -> Unit) {
        val provider = sync ?: return
        var email by remember { mutableStateOf(provider.accountEmail()) }
        var server by remember { mutableStateOf("sotto.stephens.page") }
        var formEmail by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var status by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Account") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
        }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
                val connected = email
                if (connected == null) {
                    Text("Connect your Sotto server account to keep one library on " +
                        "this phone and your desktop browser. Narration always stays " +
                        "on this device - only finished orations sync.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = server, onValueChange = { server = it },
                        label = { Text("Server") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = formEmail, onValueChange = { formEmail = it },
                        label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !busy && formEmail.isNotBlank() && password.isNotBlank(),
                        onClick = {
                            busy = true
                            lifecycleScope.launch {
                                provider.connect(server, formEmail, password)
                                    .onSuccess { m -> status = m; email = provider.accountEmail(); password = "" }
                                    .onFailure { t -> status = "Could not connect: ${t.message}" }
                                busy = false
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Connecting..." else "Connect") }
                } else {
                    Text("Connected as $connected", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !busy, onClick = {
                        busy = true
                        lifecycleScope.launch {
                            provider.syncNow()
                                .onSuccess { m -> status = m }
                                .onFailure { t -> status = "Sync failed: ${t.message}" }
                            busy = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Syncing..." else "Sync now") }
                    TextButton(onClick = { provider.disconnect(); email = null; status = "Disconnected." },
                        modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
                }
                status?.let { Spacer(Modifier.height(12.dp)); Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    // ------------------------------------------------------------------ About

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AboutScreen(onBack: () -> Unit) {
        val log by produceState(emptyList<JobLogEntity>()) {
            value = withContext(Dispatchers.IO) { db.joblog().recent(200) }
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text("About Sotto") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
        }) { pad ->
            LazyColumn(Modifier.padding(pad).padding(16.dp)) {
                item {
                    Text(
                        "Sotto narrates documents entirely on this device. The voice is " +
                        "synthetic, produced by Kokoro-82M - an openly licensed (Apache-2.0) " +
                        "model trained on documented public-domain and permissively licensed " +
                        "audio. Nothing you share with Sotto leaves the phone: this build " +
                        "holds no INTERNET permission, which anyone can verify with " +
                        "`aapt dump permissions` on the APK.\n",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Activity log (stays on this device)",
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                }
                items(log) { e ->
                    Text("${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(e.at))}  " +
                        "${e.event}  ${e.title}${if (e.detail.isNotEmpty()) " · ${e.detail}" else ""}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private fun fmtSecs(secs: Long): String =
        if (secs >= 3600) "%d h %d min".format(secs / 3600, (secs % 3600) / 60)
        else if (secs >= 60) "%d:%02d".format(secs / 60, secs % 60) else "0:%02d".format(secs)
}
