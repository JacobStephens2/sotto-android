package page.stephens.sotto.engine.narrate

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.OrationCreation
import page.stephens.sotto.engine.store.CreationResult
import page.stephens.sotto.engine.store.SottoDatabase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class NarrationCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, SottoDatabase::class.java)
        .allowMainThreadQueries().build()
    private val root = Files.createTempDirectory("narration-owner").toFile()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val models = AtomicInteger()
    private val maximumModels = AtomicInteger()
    private val generations = AtomicInteger()
    private val maximumGenerations = AtomicInteger()
    private val failure = AtomicReference<Exception?>(null)
    private val profiles = ConcurrentLinkedQueue<ModelProfile>()
    private var environment: DiagnosticEnvironment = object : DiagnosticEnvironment {
        override fun elapsedMs() = System.nanoTime() / 1_000_000
        override fun uptimeMs() = elapsedMs()
        override fun batteryPct() = 80
        override fun thermal() = "LIGHT"
        override fun pssKb() = 2048L
    }
    private var callbackDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    private var clock: () -> Long = System::currentTimeMillis
    private val creation by lazy { OrationCreation(db, root) }
    private val coordinator by lazy { NarrationCoordinator(
        db, root,
        ManagedSynthesizerFactory { profile ->
            profiles.add(profile)
            val count = models.incrementAndGet()
            maximumModels.accumulateAndGet(count, ::maxOf)
            object : ManagedSynthesizer {
                override val sampleRate = 24_000
                override fun synthesize(text: String, voiceId: String): FloatArray {
                    val active = generations.incrementAndGet()
                    maximumGenerations.accumulateAndGet(active, ::maxOf)
                    try {
                        failure.getAndSet(null)?.let { throw it }
                        if (text.startsWith("Held")) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS)) { "test did not release chunk" }
                        }
                        return floatArrayOf(1f, 2f)
                    } finally {
                        generations.decrementAndGet()
                    }
                }
                override fun close() { check(generations.get() == 0); models.decrementAndGet() }
            }
        },
        encoderFactory = {
            object : PcmEncoder {
                override fun encode(samples: FloatArray) = byteArrayOf(1, 2, 3, 4)
            }
        },
        callbackDispatcher = callbackDispatcher,
        clock = { clock() },
        diagnosticEnvironment = environment,
        creation = creation,
    ) }

    @After
    fun close() = runBlocking {
        release.countDown()
        (callbackDispatcher as? PausedCallbacks)?.flush()
        withTimeout(10_000) { coordinator.shutdown().await() }
        db.close()
        root.deleteRecursively()
        Unit
    }

    @Test
    fun `ordinary durable progress and lifecycle use the injected clock`() = runBlocking {
        val now = AtomicLong(100)
        clock = now::get
        insert("a", "Held first. " + "More. ".repeat(400))
        val savedAudio = ConcurrentLinkedQueue<OrationEntity>()
        val owner = coordinator.attach(NarrationCallbacks(onAudioReady = { savedAudio.add(it) }))
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(100L, db.orations().byId("a")!!.updatedAt)
        val started = db.joblog().recent().single { it.orationId == "a" && it.event == "started" }
        assertEquals(100L, started.at)
        // The engine saves its initial checkpoint before entering the held chunk.
        now.set(125)
        release.countDown()
        awaitStatus("a", "COMPLETE")
        val row = db.orations().byId("a")!!
        assertEquals(125L, row.updatedAt)
        assertEquals(25L, row.narratedMs)
        assertEquals(1, savedAudio.size)
        assertEquals(1, savedAudio.single().chunksDone)
        assertEquals(125L, savedAudio.single().updatedAt)
        assertTrue(db.joblog().recent().filter { it.orationId == "a" && it.event != "started" }
            .all { it.at == 125L })
        assertEquals(row.audioBytes, File(root, "a/audio.aac").length())
        assertEquals(1, db.joblog().count("a", "completed"))
    }

    @Test
    fun `gate durable progress verdict and lifecycle use the injected clock`() = runBlocking {
        val now = AtomicLong(100)
        clock = now::get
        val verdict = kotlinx.coroutines.CompletableDeferred<String>()
        val statuses = ConcurrentLinkedQueue<String>()
        val lab = coordinator.attachDiagnostics { event ->
            if (event is DiagnosticEvent.Line && event.text.startsWith("GATE COMPLETE:")) verdict.complete(event.text)
            if (event is DiagnosticEvent.Status && event.text.startsWith("gate: chunk")) {
                val row = db.orations().byId("gate")!!
                assertEquals(125L, row.updatedAt)
                assertEquals(row.audioBytes, File(root, "gate/audio.aac").length())
                assertEquals(row.chunksDone, db.joblog().count("gate", "chunk-done"))
                statuses.add(event.text)
            }
        }
        lab.request(DiagnosticRequest.Gate("Held gate. " + "More. ".repeat(400))).await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(100L, db.orations().byId("gate")!!.updatedAt)
        assertEquals(100L, db.joblog().recent().single { it.orationId == "gate" && it.event == "started" }.at)
        now.set(125)
        release.countDown()
        val terminal = withTimeout(5_000) { verdict.await() }
        val row = db.orations().byId("gate")!!
        assertEquals("COMPLETE", row.status)
        assertEquals(125L, row.updatedAt)
        assertEquals(25L, row.narratedMs)
        assertEquals(row.chunksDone, statuses.size)
        assertTrue(statuses.all { it.endsWith("thermal LIGHT · batt 80%") })
        val logs = db.joblog().recent().filter { it.orationId == "gate" && it.event != "created" }
        assertTrue(logs.filter { it.event != "started" }.all { it.at == 125L })
        assertEquals("${row.chunksDone}ch ${row.audioBytes}B narratedMs=25", logs.single { it.event == "completed" }.detail)
        assertTrue(terminal.contains("narrated 0.0 min this+prior runs, wall 0.0 min this run"))
    }

    @Test
    fun `ordinary creation does not wait for a blocked native chunk`() = runBlocking {
        insert("held", "Held ordinary.", status = "STOPPED")
        coordinator.attach().narrate("held").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val result = withTimeout(5_000) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                creation.create("Another document.", "Created while busy", "af_heart")
            }
        }
        assertTrue(result is CreationResult.Created)
        val id = (result as CreationResult.Created).id
        assertEquals("QUEUED", db.orations().byId(id)!!.status)
        assertEquals("Another document.", File(root, "$id/source.md").readText())
        assertEquals(1L, release.count)
    }

    @Test
    fun `reset fences queued callbacks from a gate whose writer already finished`() = runBlocking {
        val pending = java.util.concurrent.ConcurrentLinkedDeque<Runnable>()
        callbackDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        }
        val events = ConcurrentLinkedQueue<DiagnosticEvent>()
        val lab = coordinator.attachDiagnostics { events.add(it) }
        lab.request(DiagnosticRequest.Gate("Single gate chunk.")).await()
        awaitStatus("gate", "COMPLETE")
        withTimeout(5_000) { while (models.get() != 0) kotlinx.coroutines.yield() }
        val beforeOrdinary = pending.size

        // Starting ordinary work proves the gate writer and its terminal message drained.
        insert("ordinary", "Ordinary.", status = "STOPPED")
        val owner = coordinator.attach()
        owner.narrate("ordinary").await()
        withTimeout(5_000) { while (pending.size == beforeOrdinary) kotlinx.coroutines.yield() }
        pending.removeLast().run() // Dispatch ordinary work without delivering gate callbacks.
        awaitStatus("ordinary", "COMPLETE")

        val beforeReset = pending.size
        val reset = lab.request(DiagnosticRequest.ResetGate)
        owner.narrate("missing").await()
        withTimeout(5_000) { while (pending.size == beforeReset) kotlinx.coroutines.yield() }
        pending.removeLast().run() // Complete the playback-stop barrier first.
        assertEquals(DiagnosticResult.Reset, withTimeout(5_000) { reset.await() })
        assertNull(db.orations().byId("gate"))
        while (true) (pending.poll() ?: break).run()
        assertTrue("Reset must fence all callbacks from the deleted gate", events.isEmpty())
    }

    @Test
    fun `gate deletion fences queued terminal callbacks after the gate writer drained`() = runBlocking {
        callbackDispatcher = PausedCallbacks()
        val events = ConcurrentLinkedQueue<DiagnosticEvent>()
        val lab = coordinator.attachDiagnostics { events.add(it) }
        assertEquals(DiagnosticResult.Accepted,
            lab.request(DiagnosticRequest.Gate("Finished gate.")).await())
        awaitStatus("gate", "COMPLETE")
        insert("waiting", "Held ordinary.", status = "STOPPED")
        val owner = coordinator.attach()
        assertEquals(NarrationCommandResult.Queued, owner.narrate("waiting").await())
        // An ordinary reservation proves the preceding diagnostic finished and drained.
        withTimeout(5_000) {
            while (owner.facts.value.activeId != "waiting") kotlinx.coroutines.yield()
        }
        owner.delete("gate")
        assertEquals(NarrationCommandResult.Deleting, owner.narrate("gate").await())
        callbackDispatcher.flush()
        assertFalse("Deletion must fence a completed gate's queued terminal verdict",
            events.any { it is DiagnosticEvent.Line && it.text.startsWith("GATE COMPLETE") })
    }

    @Test
    fun `explicit gate Stop retains its terminal verdict after the durable chunk`() = runBlocking {
        val verdict = kotlinx.coroutines.CompletableDeferred<String>()
        val lab = coordinator.attachDiagnostics {
            if (it is DiagnosticEvent.Line && it.text.startsWith("gate stopped at")) verdict.complete(it.text)
        }
        lab.request(DiagnosticRequest.Gate("Held gate. " + "More. ".repeat(400))).await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        lab.request(DiagnosticRequest.Stop).await()
        assertEquals("NARRATING", db.orations().byId("gate")!!.status)
        release.countDown()
        assertTrue(withTimeout(5_000) { verdict.await() }.startsWith("gate stopped at 1/"))
        assertEquals("STOPPED", db.orations().byId("gate")!!.status)
        assertEquals(1, db.orations().byId("gate")!!.chunksDone)
    }

    @Test
    fun `measurement Stop drains generation before queued ordinary work starts`() = runBlocking {
        val lab = coordinator.attachDiagnostics()
        assertEquals(DiagnosticResult.Accepted,
            lab.request(DiagnosticRequest.Measure("Held measurement.", loops = 100)).await())
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        insert("next", "Next.", status = "STOPPED")
        val owner = coordinator.attach()
        owner.narrate("next").await()
        assertEquals(DiagnosticResult.StopRequested, lab.request(DiagnosticRequest.Stop).await())
        assertEquals("QUEUED", db.orations().byId("next")!!.status)
        assertEquals(1, models.get())
        assertEquals(1, generations.get())
        release.countDown()
        awaitStatus("next", "COMPLETE")
        assertEquals(listOf(ModelProfile(4, true), ModelProfile()), profiles.toList())
        assertEquals(1, maximumModels.get())
        assertEquals(1, maximumGenerations.get())
    }

    @Test
    fun `superseding a diagnostic fences its queued callbacks before replacement runs`() = runBlocking {
        callbackDispatcher = PausedCallbacks()
        val events = ConcurrentLinkedQueue<DiagnosticEvent>()
        val lab = coordinator.attachDiagnostics { events.add(it) }
        lab.request(DiagnosticRequest.Measure("Held measurement.", loops = 100)).await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        lab.request(DiagnosticRequest.Gate("Replacement gate.")).await()
        callbackDispatcher.flush()
        assertTrue(events.isEmpty())
        assertNull(db.orations().byId("gate"))
        assertEquals(1, models.get())
        release.countDown()
        awaitStatus("gate", "COMPLETE")
        callbackDispatcher.flush()
        assertFalse(events.any { it is DiagnosticEvent.Audio || it is DiagnosticEvent.Measured })
        assertTrue(events.any { it is DiagnosticEvent.Line && it.text.startsWith("GATE COMPLETE") })
        assertEquals(1, maximumModels.get())
        assertEquals(1, maximumGenerations.get())
    }

    @Test
    fun `new gate source is retained across explicit resumes with different bundles`() = runBlocking {
        val source = "Held gate. " + "More. ".repeat(400)
        val lab = coordinator.attachDiagnostics()
        lab.request(DiagnosticRequest.Gate(source)).await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        lab.request(DiagnosticRequest.Stop).await()
        release.countDown()
        awaitStatus("gate", "STOPPED")
        assertEquals(source, File(root, "gate/source.md").readText())
        lab.request(DiagnosticRequest.Gate("Different bundled text.")).await()
        awaitStatus("gate", "COMPLETE")
        assertEquals(source, File(root, "gate/source.md").readText())
        assertTrue(db.orations().byId("gate")!!.chunksDone > 1)
        assertEquals(1, db.joblog().count("gate", "resumed"))
    }

    @Test
    fun `queued ordinary work refuses diagnostics even without a media attachment`() = runBlocking {
        insert("a", "Waiting.")
        val lab = coordinator.attachDiagnostics()
        assertEquals(DiagnosticResult.Busy, lab.request(DiagnosticRequest.Gate("Gate.")).await())
        assertEquals(DiagnosticResult.Busy, lab.request(DiagnosticRequest.Measure("Measure.")).await())
        assertNull(db.orations().byId("gate"))
        assertEquals(0, maximumModels.get())
        assertEquals("QUEUED", db.orations().byId("a")!!.status)
    }

    @Test
    fun `ordinary retries during diagnostics retain progress and wait in FIFO order`() = runBlocking {
        val lab = coordinator.attachDiagnostics()
        assertEquals(DiagnosticResult.Accepted, lab.request(DiagnosticRequest.Gate("Held gate.")).await())
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        insert("old", "Retry.", status = "ERROR", created = 0)
        db.orations().saveProgress("old", 1, 4, 23, 1)
        File(root, "old/audio.aac").writeBytes(byteArrayOf(1, 2, 3, 4))
        insert("new", "Next.", created = 2)
        val owner = coordinator.attach()
        assertEquals(NarrationCommandResult.Queued, owner.narrate("old").await())
        assertEquals(NarrationCommandResult.Coalesced, owner.narrate("old").await())
        val queued = db.orations().byId("old")!!
        assertEquals(1, queued.chunksDone)
        assertEquals(4L, queued.audioBytes)
        assertEquals(23L, queued.narratedMs)
        assertEquals("QUEUED", db.orations().byId("new")!!.status)
        assertEquals(DiagnosticResult.Busy, lab.request(DiagnosticRequest.Measure("Superseding.")).await())
        release.countDown()
        awaitStatus("old", "COMPLETE")
        assertEquals("COMPLETE", db.orations().byId("gate")!!.status)
        val order = db.joblog().recent().reversed().filter { it.event in listOf("started", "resumed") }
        assertEquals(listOf("gate", "new", "old"), order.map { it.orationId })
        assertEquals(1, maximumModels.get())
        assertEquals(1, maximumGenerations.get())
    }

    @Test
    fun `gate reset waits for a blocked writer before removing source audio and progress`() = runBlocking {
        val lab = coordinator.attachDiagnostics()
        lab.request(DiagnosticRequest.Gate("Held gate. " + "More. ".repeat(400))).await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val reset = lab.request(DiagnosticRequest.ResetGate)
        // A subsequent request observes the deletion barrier without waiting for the writer.
        assertEquals(DiagnosticResult.Busy, lab.request(DiagnosticRequest.Gate("Replacement.")).await())
        assertFalse(reset.isCompleted)
        assertEquals("NARRATING", db.orations().byId("gate")!!.status)
        assertEquals(0, db.orations().byId("gate")!!.chunksDone)
        assertTrue(File(root, "gate/source.md").exists())
        assertTrue(File(root, "gate/audio.aac").exists())
        assertEquals(1, models.get())
        release.countDown()
        assertEquals(DiagnosticResult.Reset, withTimeout(5_000) { reset.await() })
        assertNull(db.orations().byId("gate"))
        assertFalse(File(root, "gate").exists())
        assertEquals(1, db.joblog().count("gate", "stopped"))
        assertEquals(1, db.joblog().count("gate", "deleted"))
    }

    @Test
    fun `ordinary deletion of a live gate stops playback and drains before removal`() = runBlocking {
        callbackDispatcher = PausedCallbacks()
        val callbacks = AtomicInteger()
        val lab = coordinator.attachDiagnostics { callbacks.incrementAndGet() }
        lab.request(DiagnosticRequest.Gate("Held gate. " + "More. ".repeat(400))).await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val playback = AtomicInteger()
        val owner = coordinator.attach(NarrationCallbacks(onStopPlayback = { playback.incrementAndGet() }))
        val deletion = owner.delete("gate")
        assertEquals(NarrationCommandResult.Deleting, owner.narrate("gate").await())
        callbackDispatcher.flush()
        assertEquals(1, playback.get())
        assertEquals(0, callbacks.get())
        assertFalse(deletion.isCompleted)
        assertNotNull(db.orations().byId("gate"))
        assertTrue(File(root, "gate/audio.aac").exists())
        release.countDown()
        assertEquals(NarrationCommandResult.Deleted, withTimeout(5_000) { deletion.await() })
        callbackDispatcher.flush()
        assertEquals(0, callbacks.get())
        assertNull(db.orations().byId("gate"))
        assertFalse(File(root, "gate").exists())
    }

    @Test
    fun `thread reload stops a gate and waits for generation before replacing its profile`() = runBlocking {
        val lab = coordinator.attachDiagnostics()
        lab.request(DiagnosticRequest.Gate("Held gate. " + "More. ".repeat(400))).await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val reload = lab.request(DiagnosticRequest.Threads(2))
        coordinator.attach().narrate("missing").await()
        assertFalse(reload.isCompleted)
        assertEquals(listOf(ModelProfile()), profiles.toList())
        assertEquals(1, models.get())
        release.countDown()
        assertEquals(DiagnosticResult.Configured, withTimeout(5_000) { reload.await() })
        awaitStatus("gate", "STOPPED")
        assertEquals(1, db.orations().byId("gate")!!.chunksDone)
        assertEquals(ModelProfile(2, measurement = true), profiles.last())
        assertEquals(1, maximumModels.get())
        assertEquals(1, maximumGenerations.get())
    }

    @Test
    fun `leaving Lab fences pending events while its writer drains and ordinary work waits`() = runBlocking {
        callbackDispatcher = PausedCallbacks()
        val events = AtomicInteger()
        val lab = coordinator.attachDiagnostics { events.incrementAndGet() }
        lab.request(DiagnosticRequest.Gate("Held gate. " + "More. ".repeat(400))).await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        lab.detach()
        assertEquals(DiagnosticResult.Detached, lab.request(DiagnosticRequest.Stop).await())
        insert("next", "Next.", status = "STOPPED")
        val owner = coordinator.attach()
        owner.narrate("next").await()
        callbackDispatcher.flush()
        assertEquals(0, events.get())
        assertEquals("QUEUED", db.orations().byId("next")!!.status)
        assertEquals(1, models.get())
        release.countDown()
        withTimeout(5_000) {
            while (db.joblog().count("next", "completed") == 0) {
                callbackDispatcher.flush()
                kotlinx.coroutines.yield()
            }
        }
        callbackDispatcher.flush()
        assertEquals(0, events.get())
        assertEquals("STOPPED", db.orations().byId("gate")!!.status)
        assertEquals(1, maximumModels.get())
    }

    @Test
    fun `replacement media attachment cannot stop requeue or fail a live gate`() = runBlocking {
        val lab = coordinator.attachDiagnostics()
        lab.request(DiagnosticRequest.Gate("Held gate.")).await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val first = coordinator.attach()
        first.detach()
        val replacement = coordinator.attach()
        assertEquals(NarrationCommandResult.DiagnosticOnly, replacement.narrate("gate").await())
        replacement.stop().await()
        assertEquals("NARRATING", db.orations().byId("gate")!!.status)
        assertEquals(0, db.joblog().count("gate", "stopped"))
        assertEquals(0, db.joblog().count("gate", "error"))
        release.countDown()
        awaitStatus("gate", "COMPLETE")
        assertEquals(1, db.joblog().count("gate", "started"))
        assertEquals(1, maximumGenerations.get())
    }

    @Test
    fun `legacy gate checkpoint resumes explicitly using bundled source and retains its log`() = runBlocking {
        insert("gate", "Old missing source.", status = "NARRATING")
        File(root, "gate/source.md").delete()
        db.orations().saveProgress("gate", 1, 4, 23, 1)
        File(root, "gate/audio.aac").writeBytes(byteArrayOf(1, 2, 3, 4, 99))
        val bundled = "First. " + "Next. ".repeat(400)
        val owner = coordinator.attach()
        owner.narrate("missing").await()
        val stopped = db.orations().byId("gate")!!
        assertEquals("STOPPED", stopped.status)
        assertEquals(1, stopped.chunksDone)
        assertEquals(4L, stopped.audioBytes)
        assertEquals(23L, stopped.narratedMs)
        assertEquals(0, maximumModels.get())
        assertEquals(DiagnosticResult.Accepted, coordinator.attachDiagnostics().request(DiagnosticRequest.Gate(bundled)).await())
        awaitStatus("gate", "COMPLETE")
        assertEquals(bundled, File(root, "gate/source.md").readText())
        val saved = db.orations().byId("gate")!!
        assertTrue(saved.chunksDone > 1)
        assertTrue(saved.narratedMs >= 23)
        assertEquals(saved.audioBytes, File(root, "gate/audio.aac").length())
        assertEquals(1, db.joblog().count("gate", "stopped"))
        assertEquals(1, db.joblog().count("gate", "resumed"))
    }

    @Test
    fun `measurement observations retain independent generate wall suspend and battery arithmetic`() = runBlocking {
        val elapsed = java.util.ArrayDeque(listOf(0L, 100L, 1000L, 2000L, 2500L, 5000L))
        val uptime = java.util.ArrayDeque(listOf(500L, 3500L))
        val battery = java.util.ArrayDeque(listOf(80, 79, 78))
        environment = object : DiagnosticEnvironment {
            override fun elapsedMs(): Long = elapsed.removeFirst()
            override fun uptimeMs(): Long = uptime.removeFirst()
            override fun batteryPct(): Int = battery.removeFirst()
            override fun thermal() = "MODERATE"
            override fun pssKb() = 4096L
        }
        val measured = kotlinx.coroutines.CompletableDeferred<Measurement>()
        val audio = kotlinx.coroutines.CompletableDeferred<DiagnosticEvent.Audio>()
        val lab = coordinator.attachDiagnostics {
            if (it is DiagnosticEvent.Measured) measured.complete(it.measurement)
            if (it is DiagnosticEvent.Audio) audio.complete(it)
        }
        lab.request(DiagnosticRequest.Measure("Measured text.", loops = 1)).await()
        val result = withTimeout(5_000) { measured.await() }
        assertEquals(1, result.chunks)
        assertEquals(500L, result.generateMs)
        assertEquals(4.0, result.wallSeconds, 0.0)
        assertEquals(1.0, result.suspendedSeconds, 0.0)
        assertEquals(0.00016666666666666666, result.generateRtf, 1e-15)
        assertEquals(0.000020833333333333333, result.wallRtf, 1e-15)
        assertEquals(1800.0, result.batteryPerHour, 0.0)
        assertEquals("MODERATE", result.thermal)
        assertEquals(4096L, result.peakPssKb)
        assertEquals(24_000, audio.await().sampleRate)
        assertEquals(ModelProfile(4, measurement = true), profiles.single())
    }

    @Test
    fun `diagnostics are busy while ordinary narration owns a held chunk`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        val owner = coordinator.attach()
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val lab = coordinator.attachDiagnostics()
        assertEquals(DiagnosticResult.Busy, lab.request(DiagnosticRequest.Gate("Gate text.")).await())
        assertEquals(DiagnosticResult.Busy, lab.request(DiagnosticRequest.Measure("Measure text.")).await())
        assertEquals("NARRATING", db.orations().byId("a")!!.status)
        assertFalse(coordinator.facts.value.stopping)
        assertNull(db.orations().byId("gate"))
        release.countDown()
        awaitStatus("a", "COMPLETE")
        assertEquals(1, maximumModels.get())
        assertEquals(1, maximumGenerations.get())
    }

    @Test
    fun `stop retains the writer until its durable chunk and a new request waits`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        insert("b", "Next oration.", status = "STOPPED")
        val owner = coordinator.attach()
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        owner.stop().await()
        assertEquals(NarrationCommandResult.Queued, owner.narrate("b").await())
        assertEquals("QUEUED", db.orations().byId("b")!!.status)
        assertEquals("NARRATING", db.orations().byId("a")!!.status)
        assertEquals(1, generations.get())
        release.countDown()
        awaitStatus("b", "COMPLETE")
        assertEquals("STOPPED", db.orations().byId("a")!!.status)
        assertEquals(1, db.orations().byId("a")!!.chunksDone)
        assertEquals(1, maximumModels.get())
        assertEquals(1, maximumGenerations.get())
        assertEquals(1, db.joblog().count("a", "stopped"))
    }

    @Test
    fun `busy retries retain durable progress and follow requests rather than creation time`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        insert("old", "Retry text.", status = "ERROR", created = 0)
        db.orations().saveProgress("old", 1, 4, 23, 1)
        File(root, "old/audio.aac").writeBytes(byteArrayOf(1, 2, 3, 4))
        insert("new", "New text.", created = 2)
        val owner = coordinator.attach()
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(NarrationCommandResult.Queued, owner.narrate("old").await())
        val queued = db.orations().byId("old")!!
        assertEquals(1, queued.chunksDone)
        assertEquals(4L, queued.audioBytes)
        assertEquals(23L, queued.narratedMs)
        assertEquals(NarrationCommandResult.Coalesced, owner.narrate("old").await())
        assertEquals(queued.queuedAt, db.orations().byId("old")!!.queuedAt)
        owner.stop().await()
        release.countDown()
        awaitStatus("old", "COMPLETE")
        val order = db.joblog().recent().reversed().filter { it.event in listOf("started", "resumed") }
        assertEquals(listOf("a", "new", "old"), order.map { it.orationId })
    }

    @Test
    fun `same id requests coalesce without reviving a stopping run`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        val owner = coordinator.attach()
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        owner.stop().await()
        repeat(3) { assertEquals(NarrationCommandResult.Coalesced, owner.narrate("a").await()) }
        release.countDown()
        awaitStatus("a", "STOPPED")
        assertEquals(1, db.orations().byId("a")!!.chunksDone)
        assertEquals(1, db.joblog().count("a", "started"))
        assertEquals(1, db.joblog().count("a", "stopped"))
        owner.narrate("a").await()
        awaitStatus("a", "COMPLETE")
        assertEquals(1, db.joblog().count("a", "resumed"))
        assertEquals(1, maximumGenerations.get())
    }

    @Test
    fun `delete fences admission and stops playback before waiting for the writer`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        val playbackStopped = CountDownLatch(1)
        val autoplay = AtomicInteger()
        val owner = coordinator.attach(NarrationCallbacks(
            onAudioReady = { autoplay.incrementAndGet() },
            onStopPlayback = { assertEquals("a", it); playbackStopped.countDown() },
        ))
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val deletion = owner.delete("a")
        assertTrue(playbackStopped.await(5, TimeUnit.SECONDS))
        assertEquals(NarrationCommandResult.Deleting, owner.narrate("a").await())
        assertFalse(deletion.isCompleted)
        assertNotNull(db.orations().byId("a"))
        assertTrue(File(root, "a/audio.aac").exists())
        assertEquals(0, db.joblog().count("a", "deleted"))
        release.countDown()
        assertEquals(NarrationCommandResult.Deleted, withTimeout(5_000) { deletion.await() })
        assertNull(db.orations().byId("a"))
        assertFalse(File(root, "a").exists())
        assertEquals(1, db.joblog().count("a", "deleted"))
        assertEquals(0, autoplay.get())
    }

    @Test
    fun `detach returns while held and replacement waits for writer and model drain`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        insert("b", "Replacement.", status = "STOPPED")
        val lateAudio = AtomicInteger()
        val first = coordinator.attach(NarrationCallbacks(onAudioReady = { lateAudio.incrementAndGet() }))
        first.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        first.narrate("b").await()
        first.detach()
        assertEquals(NarrationCommandResult.Detached, first.narrate("a").await())
        val replacement = coordinator.attach()
        replacement.narrate("b").await()
        assertEquals("NARRATING", db.orations().byId("a")!!.status)
        assertEquals("QUEUED", db.orations().byId("b")!!.status)
        assertEquals(1, models.get())
        release.countDown()
        awaitStatus("b", "COMPLETE")
        assertEquals("STOPPED", db.orations().byId("a")!!.status)
        assertEquals(0, db.joblog().count("a", "error"))
        assertEquals(0, lateAudio.get())
        assertEquals(1, maximumModels.get())
        assertEquals(1, maximumGenerations.get())
    }

    @Test
    fun `detach leaves queued work durable without unattached pickup`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200))
        insert("b", "Pending.", status = "STOPPED")
        val owner = coordinator.attach()
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        owner.narrate("b").await()
        owner.detach()
        release.countDown()
        withTimeout(5_000) { while (models.get() != 0) kotlinx.coroutines.yield() }
        assertEquals("QUEUED", db.orations().byId("b")!!.status)
        assertEquals(0, db.joblog().count("b", "started"))
    }

    @Test
    fun `invalid admissions never load a model`() = runBlocking {
        insert("complete", "Done.", status = "COMPLETE")
        insert("server", "Remote.", status = "STOPPED")
        db.orations().update(db.orations().byId("server")!!.copy(downloaded = false))
        insert("gate", "Diagnostic.", status = "NARRATING")
        val owner = coordinator.attach()
        assertEquals(NarrationCommandResult.Missing, owner.narrate("absent").await())
        assertEquals(NarrationCommandResult.Complete, owner.narrate("complete").await())
        assertEquals(NarrationCommandResult.ServerOnly, owner.narrate("server").await())
        assertEquals(NarrationCommandResult.DiagnosticOnly, owner.narrate("gate").await())
        assertEquals("STOPPED", db.orations().byId("gate")!!.status)
        assertEquals(0, maximumModels.get())
    }

    @Test
    fun `stop before dispatch prevents model load and source access`() = runBlocking {
        callbackDispatcher = PausedCallbacks()
        insert("a", "Text.", status = "STOPPED")
        File(root, "a/source.md").delete()
        val owner = coordinator.attach()
        owner.narrate("a").await()
        owner.stop().await()
        callbackDispatcher.flush()
        awaitStatus("a", "STOPPED")
        assertEquals(0, maximumModels.get())
        assertEquals(0, db.joblog().count("a", "error"))
    }

    @Test
    fun `missing source becomes error and pickup continues without loading its model`() = runBlocking {
        insert("a", "Lost source.")
        File(root, "a/source.md").delete()
        insert("b", "Next.", created = 2)
        coordinator.attach().narrate("a").await()
        awaitStatus("b", "COMPLETE")
        assertEquals("ERROR", db.orations().byId("a")!!.status)
        assertEquals(1, db.joblog().count("a", "error"))
        assertEquals(0, db.joblog().count("a", "started"))
    }

    @Test
    fun `storage full keeps durable checkpoint and cause while ordinary failure is error`() = runBlocking {
        insert("full", "One chunk.")
        failure.set(java.io.IOException("ENOSPC: No space left"))
        val owner = coordinator.attach()
        owner.narrate("full").await()
        awaitStatus("full", "STOPPED")
        assertEquals(NarrationCoordinator.STORAGE_FULL, db.orations().byId("full")!!.errorMessage)
        assertEquals(0L, db.orations().byId("full")!!.audioBytes)
        assertEquals(0L, File(root, "full/audio.aac").length())
        failure.set(IllegalStateException("ordinary failure"))
        insert("failed", "One chunk.", status = "STOPPED")
        owner.narrate("failed").await()
        awaitStatus("failed", "ERROR")
        assertEquals("ordinary failure", db.orations().byId("failed")!!.errorMessage)
        assertEquals(1, db.joblog().count("failed", "error"))
    }

    @Test
    fun `stop during final chunk completes and suppresses late autoplay`() = runBlocking {
        insert("a", "Held final.")
        val audio = AtomicInteger()
        val owner = coordinator.attach(NarrationCallbacks(onAudioReady = { audio.incrementAndGet() }))
        owner.narrate("a").await()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        owner.stop().await()
        release.countDown()
        awaitStatus("a", "COMPLETE")
        assertEquals(4L, db.orations().byId("a")!!.audioBytes)
        assertEquals(1, db.joblog().count("a", "completed"))
        assertEquals(0, db.joblog().count("a", "stopped"))
        assertEquals(0, audio.get())
    }

    @Test
    fun `vanished dispatched row does not strand the next queued request`() = runBlocking {
        callbackDispatcher = PausedCallbacks()
        insert("a", "Vanishing.")
        insert("b", "Next.", created = 2)
        val owner = coordinator.attach()
        owner.narrate("a").await()
        db.orations().delete("a")
        callbackDispatcher.flush()
        withTimeout(5_000) {
            while (db.orations().byId("b")!!.status != "COMPLETE" || db.joblog().count("b", "completed") == 0) {
                callbackDispatcher.flush()
                kotlinx.coroutines.yield()
            }
        }
        assertEquals(0, db.joblog().count("a", "started"))
        assertEquals(1, db.joblog().count("b", "completed"))
    }

    @Test
    fun `audio readiness observes saved progress and stale callbacks are fenced on delete`() = runBlocking {
        callbackDispatcher = PausedCallbacks()
        insert("a", "Single.")
        val audio = AtomicInteger()
        val owner = coordinator.attach(NarrationCallbacks(onAudioReady = { audio.incrementAndGet() }))
        owner.narrate("a").await()
        (callbackDispatcher as PausedCallbacks).flushNext()
        awaitStatus("a", "COMPLETE")
        val deletion = owner.delete("a")
        owner.narrate("a").await() // Wait for the deletion barrier without running queued callbacks.
        callbackDispatcher.flush()
        assertEquals(NarrationCommandResult.Deleted, withTimeout(5_000) { deletion.await() })
        assertEquals(0, audio.get())
    }

    @Test
    fun `audio readiness is emitted only after saved durable bytes`() = runBlocking {
        insert("a", "Single.")
        val ready = CountDownLatch(1)
        coordinator.attach(NarrationCallbacks(onAudioReady = { row ->
            assertTrue(row.chunksDone > 0)
            assertEquals(row.audioBytes, db.orations().byId(row.id)!!.audioBytes)
            assertEquals(row.audioBytes, File(root, "${row.id}/audio.aac").length())
            ready.countDown()
        })).narrate("a").await()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `process startup retries orphan directory removal and records it`() = runBlocking {
        val orphan = File(root, "orphan").also { it.mkdirs() }
        File(orphan, "audio.aac").writeBytes(byteArrayOf(1))
        val owner = coordinator.attach()
        owner.narrate("missing").await()
        assertFalse(orphan.exists())
        assertEquals(1, db.joblog().count("orphan", "deleted"))
    }

    @Test
    fun `recovery records a stopped lifecycle event for an interrupted gate`() = runBlocking {
        insert("gate", "Saved diagnostic source.", status = "NARRATING")
        val owner = coordinator.attach()

        assertEquals(NarrationCommandResult.Missing, owner.narrate("absent").await())
        assertEquals("STOPPED", db.orations().byId("gate")!!.status)
        assertEquals(0, maximumModels.get())
        assertEquals(1, db.joblog().count("gate", "stopped"))
    }

    @Test
    fun `replacement retry after STOPPED remains queued while old model closes`() = runBlocking {
        insert("a", "Held first. " + "More text. ".repeat(200), status = "STOPPED")
        val chunkEntered = CountDownLatch(1)
        val finishChunk = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val finishClose = CountDownLatch(1)
        val generated = AtomicInteger()
        val openModels = AtomicInteger()
        val mostModels = AtomicInteger()
        val local = NarrationCoordinator(
            db, root,
            ManagedSynthesizerFactory {
                mostModels.accumulateAndGet(openModels.incrementAndGet(), ::maxOf)
                object : ManagedSynthesizer {
                    override val sampleRate = 24_000
                    override fun synthesize(text: String, voiceId: String): FloatArray {
                        if (generated.incrementAndGet() == 1) {
                            chunkEntered.countDown()
                            check(finishChunk.await(10, TimeUnit.SECONDS)) { "chunk was not released" }
                        }
                        return floatArrayOf(1f, 2f)
                    }
                    override fun close() {
                        closeEntered.countDown()
                        check(finishClose.await(10, TimeUnit.SECONDS)) { "close was not released" }
                        openModels.decrementAndGet()
                    }
                }
            },
            encoderFactory = { object : PcmEncoder {
                override fun encode(samples: FloatArray) = byteArrayOf(1, 2, 3, 4)
            } },
            callbackDispatcher = Dispatchers.Unconfined,
        )
        try {
            val first = local.attach()
            assertEquals(NarrationCommandResult.Queued, first.narrate("a").await())
            assertTrue(chunkEntered.await(5, TimeUnit.SECONDS))
            first.detach()
            finishChunk.countDown()
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
            val stopped = db.orations().byId("a")!!
            assertEquals("STOPPED", stopped.status)
            assertEquals(1, stopped.chunksDone)
            assertEquals(1, db.joblog().count("a", "stopped"))

            val replacement = local.attach()
            assertEquals(NarrationCommandResult.Queued, replacement.narrate("a").await())
            val queued = db.orations().byId("a")!!
            assertEquals("QUEUED", queued.status)
            assertEquals(stopped.chunksDone, queued.chunksDone)
            assertEquals(stopped.audioBytes, queued.audioBytes)
            assertEquals(1, openModels.get())
            finishClose.countDown()
            awaitStatus("a", "COMPLETE")
            assertEquals(1, db.joblog().count("a", "resumed"))
            assertEquals(1, mostModels.get())
        } finally {
            finishChunk.countDown()
            finishClose.countDown()
            withTimeout(10_000) { local.shutdown().await() }
        }
    }

    private fun insert(id: String, text: String, status: String = "QUEUED", created: Long = 1) {
        val dir = File(root, id).also { it.mkdirs() }
        File(dir, "source.md").writeText(text)
        db.orations().insert(OrationEntity(
            id = id, title = id, status = status, voiceId = "af_heart", words = 100,
            chunksTotal = null, createdAt = created, updatedAt = created,
        ))
    }

    private suspend fun awaitStatus(id: String, status: String) {
        val event = when (status) { "COMPLETE" -> "completed"; "STOPPED" -> "stopped"; else -> "error" }
        withTimeout(10_000) {
            while (db.orations().byId(id)?.status != status || db.joblog().count(id, event) == 0) {
                kotlinx.coroutines.yield()
            }
        }
    }

    private fun CoroutineDispatcher.flush() { (this as PausedCallbacks).flush() }

    private class PausedCallbacks : CoroutineDispatcher() {
        private val pending = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun flush() { while (true) (pending.poll() ?: break).run() }
        suspend fun flushNext() {
            withTimeout(5_000) {
                while (pending.isEmpty()) kotlinx.coroutines.yield()
                pending.remove().run()
            }
        }
    }
}
