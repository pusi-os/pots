package com.pusios.pots.handsfree

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The microphone contract of the Vosk engine: at most one session is live, and a
 * session that was superseded while it was still loading the model must not come
 * back to life.
 *
 * The flags used to be engine fields that `start()` reset to `false` after
 * `join(SESSION_JOIN_TIMEOUT_MS)` gave up, so a worker that was still in
 * `VoskModels.ensure` was re-armed and opened a second `AudioRecord`. Here the
 * model load is the fake's slow step, which is the same shape as a first-install
 * load, and `runWithModel` stands in for the code that opens the recorder.
 */
class VoskSessionHostTest {

    /** Records what the controller would see for one session. */
    private class Recorder : SpeechToTextListener {
        val results = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<SpeechToTextError>()

        override fun onResult(text: String) {
            results += text
        }

        override fun onError(error: SpeechToTextError) {
            errors += error
        }
    }

    @Test
    fun `a session superseded while the model loads never opens the microphone`() {
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val loads = AtomicInteger()
        val microphonesOpened = AtomicInteger()
        val host = VoskSessionHost(
            joinTimeoutMs = 100,
            post = { it() },
            body = VoskSessionRunner(
                loadModel = {
                    if (loads.incrementAndGet() == 1) {
                        loadStarted.countDown()
                        releaseLoad.await(5, TimeUnit.SECONDS)
                    }
                    "model"
                },
                runWithModel = { session, model ->
                    microphonesOpened.incrementAndGet()
                    session.postResult(model)
                },
            )::run,
        )

        val superseded = Recorder()
        val first = host.start(superseded)
        assertTrue(loadStarted.await(5, TimeUnit.SECONDS), "the first session never started loading")

        val current = Recorder()
        host.start(current)
        // The join gave up while the first worker was still loading: it keeps its
        // own cancellation instead of being reset by the new session.
        assertTrue(first.isCancelRequested, "the superseded session lost its cancellation")
        assertFalse(first.isStopRequested, "the new session's start must not stop anything else")

        releaseLoad.countDown()
        first.join(5_000)
        assertFalse(first.isRunning, "the superseded worker must finish once its load returns")
        awaitUntil("the new session to report") { current.results.isNotEmpty() }

        assertEquals(1, microphonesOpened.get(), "only the live session may open the microphone")
        assertEquals(listOf("model"), current.results)
        assertTrue(superseded.results.isEmpty(), "the superseded session reported ${superseded.results}")
        assertTrue(superseded.errors.isEmpty(), "the superseded session failed with ${superseded.errors}")
    }

    @Test
    fun `a cancelled session delivers nothing to the new listener`() {
        val firstOpened = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val loads = AtomicInteger()
        val host = VoskSessionHost(
            joinTimeoutMs = 100,
            post = { it() },
            body = VoskSessionRunner(
                loadModel = { "model-${loads.incrementAndGet()}" },
                runWithModel = { session, model ->
                    if (model == "model-1") {
                        firstOpened.countDown()
                        releaseFirst.await(5, TimeUnit.SECONDS)
                    }
                    session.postResult(model)
                },
            )::run,
        )

        val superseded = Recorder()
        val first = host.start(superseded)
        assertTrue(firstOpened.await(5, TimeUnit.SECONDS), "the first session never opened the microphone")

        val current = Recorder()
        host.start(current)
        awaitUntil("the new session to report") { current.results.isNotEmpty() }

        // The old worker only now reaches its result: it must not be delivered.
        releaseFirst.countDown()
        first.join(5_000)

        assertEquals(listOf("model-2"), current.results)
        assertTrue(superseded.results.isEmpty(), "a cancelled session reported ${superseded.results}")
        assertTrue(superseded.errors.isEmpty(), "a cancelled session failed with ${superseded.errors}")
    }

    @Test
    fun `stop and cancel only reach the session they are called on`() {
        val firstOpened = CountDownLatch(1)
        val secondOpened = CountDownLatch(1)
        val releaseAll = CountDownLatch(1)
        val loads = AtomicInteger()
        val host = VoskSessionHost(
            joinTimeoutMs = 100,
            post = { it() },
            body = VoskSessionRunner(
                loadModel = { "model-${loads.incrementAndGet()}" },
                runWithModel = { _, model ->
                    if (model == "model-1") firstOpened.countDown() else secondOpened.countDown()
                    releaseAll.await(5, TimeUnit.SECONDS)
                },
            )::run,
        )

        val first = host.start(Recorder())
        assertTrue(firstOpened.await(5, TimeUnit.SECONDS), "the first session never opened the microphone")
        val second = host.start(Recorder())
        assertTrue(secondOpened.await(5, TimeUnit.SECONDS), "the second session never opened the microphone")

        host.stop()
        assertTrue(second.isStopRequested, "stop() must reach the live session")
        assertFalse(first.isStopRequested, "stop() must not reach the superseded session")

        host.cancel()
        assertTrue(second.isCancelRequested, "cancel() must reach the live session")
        assertFalse(first.isStopRequested, "cancel() must not stop the superseded session")

        releaseAll.countDown()
        first.join(5_000)
        second.join(5_000)
        assertFalse(first.isRunning)
        assertFalse(second.isRunning)
    }

    @Test
    fun `release cancels the live session and waits for it`() {
        val opened = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val host = VoskSessionHost(
            joinTimeoutMs = 5_000,
            post = { it() },
            body = VoskSessionRunner(
                loadModel = { "model" },
                runWithModel = { _, _ ->
                    opened.countDown()
                    releaseWorker.await(5, TimeUnit.SECONDS)
                },
            )::run,
        )

        val session = host.start(Recorder())
        assertTrue(opened.await(5, TimeUnit.SECONDS), "the session never opened the microphone")

        // release() has to wait for the worker, so it runs on its own thread: while
        // the worker holds its latch, release() must still be blocked. Without the
        // join in release() it returns at once and the assertion below fails.
        val releaseReturned = CountDownLatch(1)
        val releaser = Thread {
            host.release()
            releaseReturned.countDown()
        }
        releaser.start()
        assertFalse(
            releaseReturned.await(500, TimeUnit.MILLISECONDS),
            "release() returned while the worker still runs",
        )

        releaseWorker.countDown()
        assertTrue(releaseReturned.await(5, TimeUnit.SECONDS), "release() never returned")
        releaser.join(5_000)

        assertTrue(session.isCancelRequested)
        assertFalse(session.isRunning, "release() returned before the worker finished")
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("timed out waiting for $what")
    }
}
