package com.pusios.pots.handsfree

import kotlin.concurrent.thread

/**
 * One Vosk recognition attempt.
 *
 * Everything an attempt needs to unwind lives here: its own stop/cancel flags,
 * its own listener and its own worker thread. Nothing survives the attempt, so
 * starting the next session cannot re-arm this one.
 *
 * That is the point of the class. The flags used to be fields of the engine and
 * were reset to `false` right after `join(SESSION_JOIN_TIMEOUT_MS)` expired: a
 * worker that was still installing or loading the model when the join gave up
 * came back to life, ran its session and opened a second microphone next to the
 * new one.
 */
internal class VoskSession(
    private val listener: SpeechToTextListener,
    private val post: (() -> Unit) -> Unit,
    private val body: (VoskSession) -> Unit,
) {
    @Volatile
    private var stopRequested = false

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var worker: Thread? = null

    val isStopRequested: Boolean
        get() = stopRequested

    val isCancelRequested: Boolean
        get() = cancelRequested

    /** True while the worker thread is running; false before [start] and after it ends. */
    val isRunning: Boolean
        get() = worker?.isAlive == true

    fun start() {
        worker = thread(name = "vosk-stt", isDaemon = true) { body(this) }
    }

    /** Lets the session finalise what was said so far. */
    fun requestStop() {
        stopRequested = true
    }

    /** Ends the session without a result. */
    fun requestCancel() {
        cancelRequested = true
    }

    /** Waits for the worker to finish, at most [timeoutMs]; a finished worker returns at once. */
    fun join(timeoutMs: Long) {
        worker?.join(timeoutMs)
    }

    fun postLevel(level: Float) = deliver { listener.onLevel(level) }

    fun postSpeechStart() = deliver { listener.onSpeechStart() }

    fun postPartial(text: String) = deliver { listener.onPartial(text) }

    fun postFinalising() = deliver { listener.onFinalising() }

    fun postResult(text: String) = deliver { listener.onResult(text) }

    fun postError(error: SpeechToTextError) = deliver { listener.onError(error) }

    /**
     * Delivers one callback to this session's own listener, unless the session
     * was cancelled. The flag is read twice — before posting and again inside
     * the posted block — because a cancel can land between the two.
     */
    private fun deliver(block: () -> Unit) {
        if (cancelRequested) return
        post {
            if (!cancelRequested) block()
        }
    }
}

/**
 * Keeps at most one Vosk session running.
 *
 * A new [start] cancels the previous attempt and waits [joinTimeoutMs] for it to
 * unwind. That wait is a courtesy for the common case (the previous worker is
 * between two buffer reads, so it is gone in a few milliseconds); it is not what
 * stops the previous worker. Each attempt carries its own cancel flag and
 * nothing here resets it, so a worker that outlives the wait still ends as soon
 * as its blocking step returns — and never opens the microphone again.
 */
internal class VoskSessionHost(
    private val joinTimeoutMs: Long,
    private val post: (() -> Unit) -> Unit,
    private val body: (VoskSession) -> Unit,
) {
    @Volatile
    private var current: VoskSession? = null

    /**
     * Starts a new session, superseding the previous one. Returns the new
     * session: production ignores it, the tests keep it to wait for its worker.
     */
    fun start(listener: SpeechToTextListener): VoskSession {
        current?.let { previous ->
            previous.requestCancel()
            previous.join(joinTimeoutMs)
        }
        val session = VoskSession(listener, post, body)
        current = session
        session.start()
        return session
    }

    fun stop() {
        current?.requestStop()
    }

    fun cancel() {
        current?.requestCancel()
    }

    fun release() {
        val session = current ?: return
        session.requestCancel()
        session.join(joinTimeoutMs)
        current = null
    }
}

/**
 * Runs one session: load the model, then — only while the session is still
 * wanted — hand the model to [runWithModel], which owns the microphone.
 *
 * The load is the slow step (the first install of the bundled model takes longer
 * than [VoskSessionHost]'s join timeout), so the check between the two steps is
 * what actually keeps two microphones from being open: a worker superseded while
 * loading returns here and never reaches the recorder.
 */
internal class VoskSessionRunner<Model>(
    private val loadModel: () -> Model?,
    private val runWithModel: (VoskSession, Model) -> Unit,
) {
    fun run(session: VoskSession) {
        val model = loadModel()
        if (model == null) {
            session.postError(SpeechToTextError.Failed)
            return
        }
        if (session.isCancelRequested) return
        runWithModel(session, model)
    }
}
