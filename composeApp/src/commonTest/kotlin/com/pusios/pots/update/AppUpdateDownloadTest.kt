package com.pusios.pots.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Download behaviour against a mocked asset host: progress, disk-space guard, container
 * validation and the partial-file cleanup on cancel all run without touching the network.
 */
class AppUpdateDownloadTest {

    private class RecordingSink(
        private val freeBytes: Long = 10L * 1024 * 1024 * 1024,
        private val acceptWrites: Boolean = true,
    ) : UpdateFileSink {
        val written = mutableListOf<Byte>()
        var closed = false
        var deleted = false

        override fun write(buffer: ByteArray, length: Int): Boolean {
            if (!acceptWrites) return false
            for (i in 0 until length) written += buffer[i]
            return true
        }

        override fun close(): Boolean {
            closed = true
            return true
        }

        override fun delete() {
            deleted = true
        }

        override fun freeSpaceBytes(): Long = freeBytes
    }

    private fun manager(engine: MockEngine, sink: UpdateFileSink): AppUpdateManager = AppUpdateManager(
        client = HttpClient(engine),
        currentVersionName = "3.13.1",
        os = UpdateOs.Android,
        arch = UpdateArch.Arm64,
        sinkFactory = { sink },
    )

    private fun assetHost(payload: ByteArray, status: HttpStatusCode = HttpStatusCode.OK) = MockEngine { _ ->
        respond(
            content = payload,
            status = status,
            headers = headersOf(HttpHeaders.ContentLength, payload.size.toString()),
        )
    }

    private fun apkPayload(size: Int = 4096): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 0x50
        bytes[1] = 0x4B
        bytes[2] = 0x03
        bytes[3] = 0x04
        return bytes
    }

    @Test
    fun downloadsTheAssetAndReportsProgress() = runTest {
        val payload = apkPayload()
        val sink = RecordingSink()
        val events = mutableListOf<UpdateEvent>()

        val result = manager(assetHost(payload), sink).download(
            ReleaseAsset("Pots-3.14.0-android.apk", payload.size.toLong(), "https://example/a.apk"),
        ) { events += it }

        assertEquals(UpdateDownload.Ready("Pots-3.14.0-android.apk"), result)
        assertContentEquals(payload, sink.written.toByteArray())
        assertTrue(sink.closed)
        assertFalse(sink.deleted)
        val progress = events.filterIsInstance<UpdateEvent.Progress>()
        assertTrue(progress.isNotEmpty(), "the UI needs at least one progress tick")
        assertEquals(payload.size.toLong(), progress.last().progress.bytesReceived)
        assertEquals(payload.size.toLong(), progress.last().progress.totalBytes)
        assertEquals(1f, progress.last().progress.fraction)
        // An APK is installed as-is: no unpacking step.
        assertTrue(events.none { it == UpdateEvent.Unpacking })
    }

    @Test
    fun anArchiveAnnouncesItsUnpackingStep() = runTest {
        val payload = byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0x00)
        val sink = RecordingSink()
        val events = mutableListOf<UpdateEvent>()

        val result = manager(assetHost(payload), sink).download(
            ReleaseAsset("Pots-3.14.0-linux-x86_64.tar.gz", payload.size.toLong(), "https://example/a.tar.gz"),
        ) { events += it }

        assertIs<UpdateDownload.Ready>(result)
        assertTrue(events.contains(UpdateEvent.Unpacking))
    }

    @Test
    fun aFullDiskStopsBeforeAnyByteIsRequested() = runTest {
        var requests = 0
        val engine = MockEngine { _ ->
            requests++
            respond(content = apkPayload(), status = HttpStatusCode.OK)
        }
        val sink = RecordingSink(freeBytes = 1024)

        val result = manager(engine, sink).download(
            ReleaseAsset("Pots-3.14.0-android.apk", 4096, "https://example/a.apk"),
        )

        assertEquals(UpdateDownload.Failed(UpdateError.NotEnoughSpace), result)
        assertEquals(0, requests, "a doomed download must not start")
        assertTrue(sink.deleted)
    }

    @Test
    fun aTruncatedTransferIsIncompleteNotInstalled() = runTest {
        val payload = apkPayload(size = 100)
        val sink = RecordingSink()

        // The release says 4096 bytes; the host delivers 100.
        val result = manager(assetHost(payload), sink).download(
            ReleaseAsset("Pots-3.14.0-android.apk", 4096, "https://example/a.apk"),
        )

        assertEquals(UpdateDownload.Failed(UpdateError.IncompleteDownload), result)
        assertTrue(sink.deleted)
    }

    @Test
    fun aProxyErrorPageSavedAsAnApkIsRejected() = runTest {
        val payload = "<html><body>403 Forbidden</body></html>".encodeToByteArray()
        val sink = RecordingSink()

        val result = manager(assetHost(payload), sink).download(
            ReleaseAsset("Pots-3.14.0-android.apk", payload.size.toLong(), "https://example/a.apk"),
        )

        assertEquals(UpdateDownload.Failed(UpdateError.CorruptArchive), result)
        assertTrue(sink.deleted)
    }

    @Test
    fun aBrokenGzipIsRejected() = runTest {
        val payload = "not a gzip stream".encodeToByteArray()
        val sink = RecordingSink()

        val result = manager(assetHost(payload), sink).download(
            ReleaseAsset("Pots-3.14.0-linux-x86_64.tar.gz", payload.size.toLong(), "https://example/a.tar.gz"),
        )

        assertEquals(UpdateDownload.Failed(UpdateError.CorruptArchive), result)
        assertTrue(sink.deleted)
    }

    @Test
    fun aFailedWriteIsReportedAsNoSpaceLeft() = runTest {
        val sink = RecordingSink(acceptWrites = false)

        val result = manager(assetHost(apkPayload()), sink).download(
            ReleaseAsset("Pots-3.14.0-android.apk", 4096, "https://example/a.apk"),
        )

        assertEquals(UpdateDownload.Failed(UpdateError.NotEnoughSpace), result)
        assertTrue(sink.deleted)
    }

    @Test
    fun aRefusedAssetUrlIsReportedByStatus() = runTest {
        val engine = MockEngine { _ -> respondError(HttpStatusCode.Forbidden) }
        val sink = RecordingSink()

        val result = manager(engine, sink).download(
            ReleaseAsset("Pots-3.14.0-android.apk", 4096, "https://example/a.apk"),
        )

        assertEquals(UpdateDownload.Failed(UpdateError.RateLimited), result)
        assertTrue(sink.deleted)
    }

    @Test
    fun cancellingTheDownloadDeletesThePartialFile() = runTest {
        val engine = MockEngine { _ -> awaitCancellation() }
        val sink = RecordingSink()
        val manager = manager(engine, sink)

        val job = launch {
            manager.download(ReleaseAsset("Pots-3.14.0-android.apk", 4096, "https://example/a.apk"))
        }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(sink.deleted, "a cancelled download must not leave a half-written APK behind")
    }

    @Test
    fun anUnknownPlatformSkipsTheDownloadEntirely() = runTest {
        val manager = AppUpdateManager(
            client = HttpClient(MockEngine { _ -> respondError(HttpStatusCode.NotFound) }),
            currentVersionName = "3.13.1",
            os = UpdateOs.Android,
            arch = UpdateArch.Arm64,
            sinkFactory = { null },
        )

        val result = manager.download(ReleaseAsset("Pots-3.14.0-android.apk", 1, "https://example/a.apk"))

        assertEquals(UpdateDownload.Failed(UpdateError.UnsupportedPlatform), result)
    }
}
