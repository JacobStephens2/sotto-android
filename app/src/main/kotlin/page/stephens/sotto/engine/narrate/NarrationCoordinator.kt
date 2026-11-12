package page.stephens.sotto.engine.narrate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import page.stephens.sotto.engine.store.FileAudioSink
import page.stephens.sotto.engine.store.JobLog
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.RoomProgressStore
import page.stephens.sotto.engine.store.SottoDatabase
import page.stephens.sotto.engine.text.TextPipeline
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface ManagedSynthesizer : Synthesizer, AutoCloseable

fun interface ManagedSynthesizerFactory {
    fun create(profile: ModelProfile): ManagedSynthesizer
}

sealed interface NarrationCommandResult {
    data object Queued : NarrationCommandResult
    data object Coalesced : NarrationCommandResult
    data object StopRequested : NarrationCommandResult
    data object Idle : NarrationCommandResult
    data object Deleted : NarrationCommandResult
    data object Missing : NarrationCommandResult
    data object Complete : NarrationCommandResult
    data object ServerOnly : NarrationCommandResult
    data object DiagnosticOnly : NarrationCommandResult
    data object Detached : NarrationCommandResult
    data object Deleting : NarrationCommandResult
    data class Failed(val message: String) : NarrationCommandResult
}

data class NarrationFacts(val activeId: String? = null, val stopping: Boolean = false)

// These callbacks run on the playback thread. They must return promptly; the
// module supplies saved metadata so listen-now needs no second asynchronous read.
data class NarrationCallbacks(
    val onAudioReady: (OrationEntity) -> Unit = {},
    val onStopPlayback: (String) -> Unit = {},
)

class AttachedNarrator internal constructor(
    private val coordinator: NarrationCoordinator,
    internal val callbacks: AtomicReference<NarrationCallbacks?>,
) {
    internal val valid = AtomicBoolean(true)
    internal val latestRun = AtomicReference<NarrationCoordinator.Run?>(null)
    val facts: StateFlow<NarrationFacts> get() = coordinator.facts

    fun narrate(id: String): Deferred<NarrationCommandResult> = coordinator.submit(this, Command.Narrate(id))
    fun stop(): Deferred<NarrationCommandResult> = coordinator.submit(this, Command.Stop)
    fun delete(id: String): Deferred<NarrationCommandResult> = coordinator.submit(this, Command.Delete(id))
    fun detach() = coordinator.detach(this)

    internal fun invalidate() = synchronized(this) {
        valid.set(false)
        callbacks.set(null)
        latestRun.get()?.stop?.set(true)
    }
}

internal sealed interface Command {
    data class Narrate(val id: String) : Command
    data object Stop : Command
    data class Delete(val id: String) : Command
}

/** Process lifetime, manually wired once. Control never waits for a chunk;
 *  the execution lane owns model construction, writes, outcomes and release. */
class NarrationCoordinator(
    private val db: SottoDatabase,
    private val orationsDir: File,
    private val modelFactory: ManagedSynthesizerFactory,
    private val encoderFactory: (Int) -> PcmEncoder,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val clock: () -> Long = System::currentTimeMillis,
    private val diagnosticEnvironment: DiagnosticEnvironment = object : DiagnosticEnvironment {
        override fun elapsedMs() = System.nanoTime() / 1_000_000
        override fun uptimeMs() = elapsedMs()
        override fun batteryPct() = -1
        override fun thermal() = "unknown"
        override fun pssKb() = 0L
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lane = Executors.newSingleThreadExecutor { r -> Thread(r, "narration-writer") }.asCoroutineDispatcher()
    private val mailbox = Channel<Message>(Channel.UNLIMITED)
    private val attachment = AtomicReference<AttachedNarrator?>(null)
    private val diagnosticAttachment = AtomicReference<AttachedDiagnostics?>(null)
    private val deleting = ConcurrentHashMap.newKeySet<String>()
    private val pendingDeletes = linkedMapOf<String, Deletion>()
    private val mutableFacts = MutableStateFlow(NarrationFacts())
    val facts: StateFlow<NarrationFacts> = mutableFacts.asStateFlow()
    private var owner: AttachedNarrator? = null
    private var active: Run? = null
    private var diagnostic: DiagnosticRun? = null
    private var pendingDiagnostic: DiagnosticRun? = null
    private var laneBusy = false
    private var model: ManagedSynthesizer? = null
    private var modelOwner: Any? = null
    private var shutdownReply: CompletableDeferred<Unit>? = null
    private var initializationFailure: Throwable? = null

    internal class Run(val owner: AttachedNarrator, val id: String) {
        val stop = AtomicBoolean(false)
        var dispatchPosted = false
        var dispatched = false
        var audioPosted = false
    }

    internal class DiagnosticRun(
        val owner: AttachedDiagnostics,
        val request: DiagnosticRequest,
        val threads: Int,
        val reply: CompletableDeferred<DiagnosticResult>,
    ) {
        val stop = AtomicBoolean(false)
        val terminalAllowed = AtomicBoolean(true)
    }

    private class Deletion(
        val id: String,
        val replies: MutableList<CompletableDeferred<NarrationCommandResult>>,
        var playbackStopped: Boolean = false,
    )

    private sealed interface Message {
        data class Attach(val owner: AttachedNarrator) : Message
        data class Detach(val owner: AttachedNarrator) : Message
        data class Request(
            val owner: AttachedNarrator,
            val command: Command,
            val reply: CompletableDeferred<NarrationCommandResult>,
        ) : Message
        data class Dispatch(val run: Run) : Message
        data class PlaybackStopped(val id: String, val failure: Throwable?) : Message
        data class Finished(val run: Run) : Message
        data class Removed(val id: String, val result: NarrationCommandResult) : Message
        data class DiagnosticCommand(
            val owner: AttachedDiagnostics,
            val request: DiagnosticRequest,
            val reply: CompletableDeferred<DiagnosticResult>,
        ) : Message
        data class DiagnosticFinished(val run: DiagnosticRun, val result: DiagnosticResult) : Message
        data object DiagnosticDetached : Message
        data object ClosedModel : Message
        data class Shutdown(val reply: CompletableDeferred<Unit>) : Message
    }

    init {
        scope.launch {
            try {
                recoverInterrupted()
                sweepOrphans()
            } catch (t: Exception) {
                initializationFailure = t
            }
            for (message in mailbox) {
                handle(message)
                reserveRun()
                // Everything submitted before dispatch gets a chance to stop or
                // delete the reserved run before its model can be constructed.
                while (true) {
                    val pending = mailbox.tryReceive().getOrNull() ?: break
                    handle(pending)
                    reserveRun()
                }
                pump()
            }
        }
    }

    fun attach(callbacks: NarrationCallbacks = NarrationCallbacks()): AttachedNarrator {
        val next = AttachedNarrator(this, AtomicReference(callbacks))
        attachment.getAndSet(next)?.invalidate()
        mailbox.trySend(Message.Attach(next))
        return next
    }

    fun attachDiagnostics(onEvent: (DiagnosticEvent) -> Unit = {}): AttachedDiagnostics {
        val next = AttachedDiagnostics(this, AtomicReference(onEvent))
        diagnosticAttachment.getAndSet(next)?.invalidate()
        mailbox.trySend(Message.DiagnosticDetached)
        return next
    }

    internal fun submitDiagnostic(owner: AttachedDiagnostics, request: DiagnosticRequest): Deferred<DiagnosticResult> {
        val reply = CompletableDeferred<DiagnosticResult>()
        if (!owner.valid.get() || !mailbox.trySend(Message.DiagnosticCommand(owner, request, reply)).isSuccess) {
            reply.complete(DiagnosticResult.Detached)
        }
        return reply
    }

    internal fun detachDiagnostics(owner: AttachedDiagnostics) {
        owner.invalidate()
        diagnosticAttachment.compareAndSet(owner, null)
        mailbox.trySend(Message.DiagnosticDetached)
    }

    internal fun submit(owner: AttachedNarrator, command: Command): Deferred<NarrationCommandResult> {
        val reply = CompletableDeferred<NarrationCommandResult>()
        if (!owner.valid.get() || !mailbox.trySend(Message.Request(owner, command, reply)).isSuccess) {
            reply.complete(NarrationCommandResult.Detached)
        }
        return reply
    }

    internal fun detach(owner: AttachedNarrator) {
        owner.invalidate()
        attachment.compareAndSet(owner, null)
        mailbox.trySend(Message.Detach(owner))
    }

    // Application shutdown and isolated tests use the same safe drain as detach.
    fun shutdown(): Deferred<Unit> {
        attachment.getAndSet(null)?.invalidate()
        diagnosticAttachment.getAndSet(null)?.invalidate()
        val reply = CompletableDeferred<Unit>()
        if (!mailbox.trySend(Message.Shutdown(reply)).isSuccess) reply.complete(Unit)
        return reply
    }

    private fun handle(message: Message) {
        when (message) {
            is Message.Attach -> {
                owner = message.owner.takeIf { it.valid.get() }
                active?.takeIf { it.owner !== owner }?.stop?.set(true)
            }
            is Message.Detach -> {
                if (owner === message.owner) owner = null
                active?.takeIf { it.owner === message.owner }?.stop?.set(true)
                updateFacts()
            }
            is Message.Request -> {
                try {
                    when {
                        !message.owner.valid.get() -> message.reply.complete(NarrationCommandResult.Detached)
                        initializationFailure != null -> message.reply.complete(failed(initializationFailure!!))
                        else -> accept(message)
                    }
                } catch (t: Exception) {
                    message.reply.complete(failed(t))
                }
            }
            is Message.Dispatch -> if (active === message.run) message.run.dispatched = true
            is Message.Finished -> {
                if (active === message.run) active = null
                laneBusy = false
                updateFacts()
            }
            is Message.PlaybackStopped -> {
                val deletion = pendingDeletes[message.id] ?: return
                if (message.failure == null) deletion.playbackStopped = true
                else finishDeletion(message.id, failed(message.failure))
            }
            is Message.Removed -> {
                laneBusy = false
                finishDeletion(message.id, message.result)
            }
            is Message.DiagnosticCommand -> try {
                acceptDiagnostic(message)
            } catch (t: Exception) {
                message.reply.complete(DiagnosticResult.Failed(t.message ?: t.toString()))
            }
            is Message.DiagnosticFinished -> {
                diagnostic = null
                laneBusy = false
                message.run.reply.complete(message.result)
            }
            Message.DiagnosticDetached -> {
                diagnostic?.takeIf { !it.owner.valid.get() }?.stop?.set(true)
                pendingDiagnostic?.takeIf { !it.owner.valid.get() }?.let {
                    it.reply.complete(DiagnosticResult.Detached)
                    pendingDiagnostic = null
                }
            }
            Message.ClosedModel -> laneBusy = false
            is Message.Shutdown -> {
                owner = null
                active?.stop?.set(true)
                diagnostic?.stop?.set(true)
                shutdownReply = message.reply
            }
        }
    }

    private fun acceptDiagnostic(message: Message.DiagnosticCommand) {
        val lab = message.owner
        val request = message.request
        if (!lab.valid.get()) { message.reply.complete(DiagnosticResult.Detached); return }
        initializationFailure?.let {
            message.reply.complete(DiagnosticResult.Failed(it.message ?: it.toString())); return
        }
        if (request == DiagnosticRequest.Stop) {
            diagnostic?.takeIf { it.owner === lab }?.stop?.set(true)
            pendingDiagnostic?.takeIf { it.owner === lab }?.let {
                it.stop.set(true)
                it.reply.complete(DiagnosticResult.Superseded)
                pendingDiagnostic = null
            }
            message.reply.complete(DiagnosticResult.StopRequested)
            return
        }
        if (request == DiagnosticRequest.ResetGate) {
            diagnostic?.takeIf { it.request is DiagnosticRequest.Gate }?.stop?.set(true)
            pendingDiagnostic?.takeIf { it.request is DiagnosticRequest.Gate }?.let {
                it.stop.set(true)
                it.reply.complete(DiagnosticResult.Superseded)
                pendingDiagnostic = null
            }
            val deletion = CompletableDeferred<NarrationCommandResult>()
            beginDeletion(owner, "gate", deletion)
            scope.launch {
                val result = deletion.await()
                message.reply.complete(if (result == NarrationCommandResult.Deleted) DiagnosticResult.Reset
                    else DiagnosticResult.Failed(result.toString()))
            }
            return
        }
        val start = request is DiagnosticRequest.Gate || request is DiagnosticRequest.Measure
        val controlsAdmittedRun = request is DiagnosticRequest.Threads &&
            (diagnostic?.owner === lab || pendingDiagnostic?.owner === lab)
        if (active != null || (!controlsAdmittedRun && db.orations().nextQueued() != null) || pendingDeletes.isNotEmpty()) {
            message.reply.complete(DiagnosticResult.Busy)
            return
        }
        when (request) {
            is DiagnosticRequest.Threads -> require(request.count in 2..4) { "Threads must be 2, 3, or 4" }
            is DiagnosticRequest.Measure -> require(request.loops > 0 && request.minutes >= 0) { "Invalid measurement duration" }
            else -> Unit
        }
        diagnostic?.stop?.set(true)
        pendingDiagnostic?.let { it.stop.set(true); it.reply.complete(DiagnosticResult.Superseded) }
        if (request is DiagnosticRequest.Threads) lab.threads = request.count
        val next = DiagnosticRun(lab, request, lab.threads, message.reply)
        lab.latest.set(next)
        pendingDiagnostic = next
        if (start) message.reply.complete(DiagnosticResult.Accepted)
    }

    private fun accept(request: Message.Request) {
        val result = when (val command = request.command) {
            is Command.Narrate -> admit(command.id)
            Command.Stop -> {
                active?.stop?.set(true)
                updateFacts()
                if (active == null) NarrationCommandResult.Idle else NarrationCommandResult.StopRequested
            }
            is Command.Delete -> {
                beginDeletion(request.owner, command.id, request.reply)
                return
            }
        }
        request.reply.complete(result)
    }

    private fun admit(id: String): NarrationCommandResult {
        if (id in deleting) return NarrationCommandResult.Deleting
        val row = db.orations().byId(id) ?: return NarrationCommandResult.Missing
        if (row.status == "COMPLETE") return NarrationCommandResult.Complete
        if (!row.downloaded) return NarrationCommandResult.ServerOnly
        if (id == "gate") return NarrationCommandResult.DiagnosticOnly
        if (row.status == "QUEUED" || row.status == "NARRATING") return NarrationCommandResult.Coalesced
        if (db.orations().enqueueRetry(id, clock()) == 0) return NarrationCommandResult.Missing
        JobLog(db.joblog()).log("queued", row.title, id, "retry requested")
        return NarrationCommandResult.Queued
    }

    private fun reserveRun() {
        if (active != null || diagnostic != null || pendingDiagnostic != null || laneBusy ||
            pendingDeletes.isNotEmpty() || initializationFailure != null) return
        val current = owner?.takeIf { it.valid.get() } ?: return
        if (shutdownReply != null) return
        val row = db.orations().nextQueued() ?: return
        if (row.id in deleting) return
        active = Run(current, row.id).also { current.latestRun.set(it) }
        updateFacts()
    }

    private fun pump() {
        if (laneBusy) return
        val run = active
        if (run != null) {
            if (run.stop.get() || !run.owner.valid.get()) run.dispatched = true
            if (!run.dispatchPosted) {
                run.dispatchPosted = true
                scope.launch(callbackDispatcher) { mailbox.send(Message.Dispatch(run)) }
            }
            if (!run.dispatched) return
            laneBusy = true
            scope.launch(lane) {
                try {
                    execute(run)
                } finally {
                    if (!run.owner.valid.get()) closeModel()
                    mailbox.send(Message.Finished(run))
                }
            }
            return
        }
        pendingDeletes.values.firstOrNull { it.playbackStopped }?.let { deletion ->
            laneBusy = true
            scope.launch(lane) {
                val result = try {
                    val row = db.orations().byId(deletion.id)
                    db.orations().delete(deletion.id)
                    check(File(orationsDir, deletion.id).deleteRecursively()) { "Could not remove oration files" }
                    JobLog(db.joblog()).log("deleted", row?.title ?: deletion.id, deletion.id)
                    NarrationCommandResult.Deleted
                } catch (t: Exception) {
                    runCatching { JobLog(db.joblog()).log("error", deletion.id, deletion.id, "deletion failed: $t") }
                    failed(t)
                }
                mailbox.send(Message.Removed(deletion.id, result))
            }
            return
        }
        pendingDiagnostic?.let { next ->
            pendingDiagnostic = null
            if (!next.owner.valid.get() || next.stop.get()) {
                next.reply.complete(DiagnosticResult.Detached)
                reserveRun()
                pump()
                return
            }
            diagnostic = next
            laneBusy = true
            scope.launch(lane) {
                var result: DiagnosticResult = DiagnosticResult.Configured
                try {
                    closeModel()
                    if (!next.stop.get() && next.owner.valid.get()) executeDiagnostic(next)
                } catch (t: Throwable) {
                    result = DiagnosticResult.Failed(t.message ?: t.toString())
                    postDiagnostic(next, DiagnosticEvent.Line("ERROR: $t"))
                } finally {
                    try { closeModel() } finally { mailbox.send(Message.DiagnosticFinished(next, result)) }
                }
            }
            return
        }
        if (modelOwner != null && (modelOwner !== owner || owner?.valid?.get() != true)) {
            laneBusy = true
            scope.launch(lane) { closeModel(); mailbox.send(Message.ClosedModel) }
            return
        }
        if (shutdownReply != null && pendingDeletes.isEmpty()) {
            shutdownReply?.complete(Unit)
            mailbox.close()
            lane.close()
            scope.cancel()
        }
    }

    private fun beginDeletion(
        owner: AttachedNarrator?,
        id: String,
        reply: CompletableDeferred<NarrationCommandResult>,
    ) {
        pendingDeletes[id]?.let { it.replies.add(reply); return }
        deleting.add(id)
        active?.takeIf { it.id == id }?.stop?.set(true)
        if (id == "gate") {
            diagnostic?.takeIf { it.request is DiagnosticRequest.Gate }?.let {
                it.terminalAllowed.set(false)
                it.stop.set(true)
            }
            pendingDiagnostic?.takeIf { it.request is DiagnosticRequest.Gate }?.let {
                it.stop.set(true)
                it.reply.complete(DiagnosticResult.Superseded)
                pendingDiagnostic = null
            }
        }
        owner?.latestRun?.get()?.takeIf { it.id == id }?.stop?.set(true)
        pendingDeletes[id] = Deletion(id, mutableListOf(reply))
        updateFacts()
        scope.launch(callbackDispatcher) {
            val failure = runCatching {
                if (owner != null) synchronized(owner) { owner.callbacks.get()?.onStopPlayback?.invoke(id) }
            }.exceptionOrNull()
            mailbox.send(Message.PlaybackStopped(id, failure))
        }
    }

    private fun finishDeletion(id: String, result: NarrationCommandResult) {
        pendingDeletes.remove(id)?.replies?.forEach { it.complete(result) }
        deleting.remove(id)
    }

    private fun execute(run: Run) {
        val row = db.orations().byId(run.id) ?: return
        val log = JobLog(db.joblog())
        try {
            if (run.stop.get() || !run.owner.valid.get()) {
                db.orations().setStatus(row.id, "STOPPED", clock())
                log.log("stopped", row.title, row.id, "before the first chunk")
                return
            }
            val cleaned = TextPipeline.cleanMarkdown(File(orationsDir, "${row.id}/source.md").readText())
            db.orations().setStatus(row.id, "NARRATING", clock())
            log.log(if (row.chunksDone > 0) "resumed" else "started", row.title, row.id)
            if (modelOwner !== run.owner) closeModel()
            // Detach and stop can also arrive while source I/O is in flight.
            if (run.stop.get() || !run.owner.valid.get()) {
                db.orations().setStatus(row.id, "STOPPED", clock())
                log.log("stopped", row.title, row.id, "before model load")
                return
            }
            val synth = model ?: modelFactory.create(ModelProfile()).also { model = it; modelOwner = run.owner }
            val result = FileAudioSink(File(orationsDir, "${row.id}/audio.aac")).use { sink ->
                NarrationEngine(synth, encoderFactory(synth.sampleRate), RoomProgressStore(db.orations()), clock)
                    .narrate(row.id, cleaned, row.voiceId, sink,
                        shouldStop = { run.stop.get() || !run.owner.valid.get() },
                        onProgress = { done, total ->
                            db.orations().setChunksTotal(row.id, total, clock())
                            log.log("chunk-done", row.title, row.id, "$done/$total")
                            if (!run.audioPosted) {
                                run.audioPosted = true
                                db.orations().byId(row.id)?.let { saved -> postAudio(run, saved) }
                            }
                        })
            }
            when (result) {
                is NarrationResult.Complete -> {
                    db.orations().setStatus(row.id, "COMPLETE", clock())
                    log.log("completed", row.title, row.id, "${result.chunks}ch ${result.audioBytes}B")
                }
                is NarrationResult.Stopped -> {
                    db.orations().setStatus(row.id, "STOPPED", clock())
                    log.log("stopped", row.title, row.id, "${result.chunksDone}/${result.totalChunks}")
                }
                is NarrationResult.StorageFull -> {
                    db.orations().setStatus(row.id, "STOPPED", clock(), STORAGE_FULL)
                    log.log("stopped", row.title, row.id, "storage full at ${result.chunksDone}/${result.totalChunks}")
                }
                is NarrationResult.Failed -> throw result.cause
            }
        } catch (t: Throwable) {
            db.orations().setStatus(row.id, "ERROR", clock(), t.message)
            log.log("error", row.title, row.id, t.toString().take(160))
        }
    }

    private fun postAudio(run: Run, row: OrationEntity) {
        scope.launch(callbackDispatcher) {
            synchronized(run.owner) {
                if (run.owner.valid.get() && run.owner.latestRun.get() === run &&
                    !run.stop.get() && run.id !in deleting
                ) run.owner.callbacks.get()?.onAudioReady?.invoke(row)
            }
        }
    }

    private fun closeModel() {
        try { model?.close() } finally { model = null; modelOwner = null }
    }

    private fun executeDiagnostic(run: DiagnosticRun) {
        val execution = DiagnosticExecution(db, orationsDir, encoderFactory, clock, diagnosticEnvironment)
        val profile = ModelProfile(run.threads, run.request !is DiagnosticRequest.Gate)
        val started = diagnosticEnvironment.elapsedMs()
        val synth = modelFactory.create(profile).also { model = it; modelOwner = run.owner }
        postDiagnostic(run, DiagnosticEvent.Line("model-load %.1fs (threads=${run.threads})"
            .format((diagnosticEnvironment.elapsedMs() - started) / 1000.0)))
        val stopped = { run.stop.get() || !run.owner.valid.get() }
        if (stopped()) return
        val emit = { event: DiagnosticEvent -> postDiagnostic(run, event) }
        val finish = { event: DiagnosticEvent -> postDiagnostic(run, event, terminal = true) }
        when (val request = run.request) {
            is DiagnosticRequest.Gate -> execution.gate(request.bundledText, synth, stopped, emit, finish)
            is DiagnosticRequest.Measure -> execution.measure(request, run.threads, synth, stopped, emit, finish)
            is DiagnosticRequest.Threads -> postDiagnostic(run, DiagnosticEvent.Status("Threads: ${run.threads} configured"))
            else -> error("Not an execution request")
        }
    }

    private fun postDiagnostic(run: DiagnosticRun, event: DiagnosticEvent, terminal: Boolean = false) {
        scope.launch(callbackDispatcher) {
            synchronized(run.owner) {
                // Explicit Stop keeps its terminal report; replacement, detach
                // and deletion still fence every event from the previous run.
                if (run.owner.valid.get() && run.owner.latest.get() === run &&
                    (!run.stop.get() || (terminal && run.terminalAllowed.get()))) {
                    run.owner.callback.get()?.invoke(event)
                }
            }
        }
    }

    private fun updateFacts() {
        mutableFacts.value = NarrationFacts(active?.id, active?.stop?.get() ?: false)
    }

    private fun sweepOrphans() {
        orationsDir.listFiles()?.filter { it.isDirectory && db.orations().byId(it.name) == null }?.forEach {
            val removed = it.deleteRecursively()
            JobLog(db.joblog()).log(if (removed) "deleted" else "error", it.name, it.name, "orphan cleanup")
        }
    }

    private fun recoverInterrupted() {
        db.runInTransaction {
            val interrupted = db.orations().all().filter {
                it.status == "NARRATING" || (it.id == "gate" && it.status == "QUEUED")
            }
            db.orations().requeueInterrupted(clock())
            db.orations().stopInterruptedGate(clock())
            val log = JobLog(db.joblog())
            interrupted.filter { it.downloaded }.forEach {
                log.log(if (it.id == "gate") "stopped" else "queued", it.title, it.id, "process recovery")
            }
        }
    }

    private fun failed(t: Throwable) = NarrationCommandResult.Failed(t.message ?: t.javaClass.simpleName)

    companion object {
        const val STORAGE_FULL = "Storage full - free some space, then tap to resume"
    }
}
