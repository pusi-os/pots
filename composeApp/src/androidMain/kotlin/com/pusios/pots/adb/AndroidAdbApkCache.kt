// [AdbApkCache] on Android: streams an APK from an http(s) URL into the app cache so the backend
// can push it. Streaming rather than buffering, because a 300 MB APK does not fit a low-end
// phone's heap; a half-downloaded file is deleted so it is never installed.

package com.pusios.pots.adb

import android.content.Context
import com.pusios.pots.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024

/** An APK that is bigger than this is refused rather than streamed until the disk is full. */
private const val MAX_APK_BYTES = 512L * 1024 * 1024

/**
 * [AdbApkCache] on Android: streams the APK into `cacheDir/adb` and hands the path to the backend,
 * which pushes it and runs `pm install`.
 *
 * Streaming rather than `bodyAsBytes()`: an APK can be a couple of hundred megabytes, and the
 * phone that runs the agent is often the cheap one. The file is deleted on any failure so a
 * half-downloaded APK is never installed.
 */
class AndroidAdbApkCache(private val context: Context) : AdbApkCache {

    private val client: HttpClient by lazy {
        httpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 10 * 60 * 1000
                connectTimeoutMillis = 15_000
            }
        }
    }

    override suspend fun download(url: String): String = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, ADB_DIRECTORY).apply { mkdirs() }
        val target = File(directory, "install-${System.currentTimeMillis()}.apk")
        try {
            client.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) {
                    throw IOException("HTTP ${response.status.value} for $url")
                }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var written = 0L
                FileOutputStream(target).use { output ->
                    while (!channel.isClosedForRead) {
                        currentCoroutineContext().ensureActive()
                        val read = channel.readAvailable(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > MAX_APK_BYTES) throw IOException("the APK is larger than 512 MB")
                        output.write(buffer, 0, read)
                    }
                }
                if (written == 0L) throw IOException("empty download from $url")
            }
            target.absolutePath
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }
}