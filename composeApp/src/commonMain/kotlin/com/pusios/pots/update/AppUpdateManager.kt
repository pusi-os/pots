package com.pusios.pots.update

import com.pusios.pots.Version
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val USER_AGENT = "Pots/${Version.appVersion}"
private const val DOWNLOAD_CHUNK_BYTES = 64 * 1024

/** Headroom kept free on top of the download itself, so a full disk cannot eat the database. */
internal const val UPDATE_DISK_SPACE_BUFFER_BYTES = 64L * 1024 * 1024

/**
 * Checks GitHub for a newer Pots build and downloads it on explicit user action.
 *
 * Nothing here runs on its own: the settings screen calls [check] when the user presses the
 * button, and [download] only after the check found a newer release.
 */
class AppUpdateManager(
    private val client: HttpClient,
    private val currentVersionName: String,
    private val os: UpdateOs,
    private val arch: UpdateArch,
    private val sinkFactory: (String) -> UpdateFileSink? = ::openUpdateFileSink,
) {
    /** Compares the published release with the running build; never throws on network trouble. */
    suspend fun check(): UpdateCheck {
        val response = try {
            client.get(POTS_RELEASES_URL) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return UpdateCheck.Failed(UpdateError.NetworkError)
        }
        if (!response.status.isSuccess()) return UpdateCheck.Failed(statusError(response.status.value))

        val body = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return UpdateCheck.Failed(UpdateError.NetworkError)
        }

        val release = parseRelease(body) ?: return UpdateCheck.Failed(UpdateError.InvalidResponse)
        val currentCode = versionCodeOf(currentVersionName) ?: return UpdateCheck.Failed(UpdateError.InvalidResponse)
        if (release.versionCode <= currentCode) return UpdateCheck.UpToDate(release.versionName)

        val asset = selectAsset(release, os, arch) ?: return UpdateCheck.Failed(UpdateError.NoAssetForPlatform)
        return UpdateCheck.Available(release, asset)
    }

    /**
     * Streams [asset] into `updates/<name>` and validates what landed there.
     *
     * Cancelling the calling coroutine aborts the transfer and deletes the partial file, which
     * is what the «Отмена» button does.
     */
    suspend fun download(asset: ReleaseAsset, onEvent: (UpdateEvent) -> Unit = {}): UpdateDownload {
        val sink = sinkFactory(asset.name) ?: return UpdateDownload.Failed(UpdateError.UnsupportedPlatform)
        var completed = false
        try {
            val freeSpace = sink.freeSpaceBytes()
            if (freeSpace in 0 until asset.sizeBytes + UPDATE_DISK_SPACE_BUFFER_BYTES) {
                return UpdateDownload.Failed(UpdateError.NotEnoughSpace)
            }

            var received = 0L
            var head = ByteArray(0)
            var failure: UpdateError? = null
            var lastPercent = -1
            val startedAt = TimeSource.Monotonic.markNow()

            client.prepareGet(asset.downloadUrl) { header(HttpHeaders.UserAgent, USER_AGENT) }.execute { response ->
                if (!response.status.isSuccess()) {
                    failure = statusError(response.status.value)
                    return@execute
                }
                val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
                val total = asset.sizeBytes.takeIf { it > 0L } ?: declared
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)

                while (!channel.isClosedForRead) {
                    currentCoroutineContext().ensureActive()
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    if (received == 0L) head = buffer.copyOf(minOf(read, 8))
                    if (!sink.write(buffer, read)) {
                        failure = UpdateError.NotEnoughSpace
                        break
                    }
                    received += read
                    val percent = if (total > 0L) (received * 100 / total).toInt() else -1
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onEvent(UpdateEvent.Progress(progressOf(received, total, startedAt)))
                    }
                }
            }

            failure?.let { return UpdateDownload.Failed(it) }
            if (!sink.close()) return UpdateDownload.Failed(UpdateError.NotEnoughSpace)
            if (received == 0L || (asset.sizeBytes > 0L && received != asset.sizeBytes)) {
                return UpdateDownload.Failed(UpdateError.IncompleteDownload)
            }
            if (requiresUnpacking(asset.name)) onEvent(UpdateEvent.Unpacking)
            if (!archiveHeaderMatches(asset.name, head)) return UpdateDownload.Failed(UpdateError.CorruptArchive)

            completed = true
            return UpdateDownload.Ready(asset.name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return UpdateDownload.Failed(UpdateError.NetworkError)
        } finally {
            sink.close()
            if (!completed) sink.delete()
        }
    }

    private fun progressOf(received: Long, total: Long, startedAt: TimeMark): DownloadProgress {
        val elapsedMs = startedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(1L)
        return DownloadProgress(
            bytesReceived = received,
            totalBytes = total,
            bytesPerSecond = received * 1000 / elapsedMs,
        )
    }

    private fun statusError(status: Int): UpdateError = when (status) {
        403, 429 -> UpdateError.RateLimited
        404 -> UpdateError.NotFound
        else -> UpdateError.ServerError
    }
}
