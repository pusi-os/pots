package com.pusios.pots.update

import com.pusios.pots.getAppFilesDirectory
import java.io.File
import java.io.FileOutputStream

/** Streams a downloaded update into the app's own `updates/` folder; shared by Android and desktop. */
private class JvmUpdateFileSink(private val file: File) : UpdateFileSink {
    private val stream = FileOutputStream(file)

    override fun write(buffer: ByteArray, length: Int): Boolean = try {
        stream.write(buffer, 0, length)
        true
    } catch (_: Exception) {
        false
    }

    override fun close(): Boolean = try {
        stream.flush()
        stream.close()
        true
    } catch (_: Exception) {
        false
    }

    override fun delete() {
        runCatching { file.delete() }
    }

    override fun freeSpaceBytes(): Long = runCatching { file.parentFile?.usableSpace ?: -1L }.getOrDefault(-1L)
}

actual fun openUpdateFileSink(fileName: String): UpdateFileSink? = try {
    val directory = File(getAppFilesDirectory(), "updates")
    directory.mkdirs()
    JvmUpdateFileSink(File(directory, fileName.substringAfterLast('/')))
} catch (_: Exception) {
    null
}

actual val hostArchitecture: UpdateArch
    get() = when (System.getProperty("os.arch")?.lowercase()) {
        "aarch64", "arm64" -> UpdateArch.Arm64
        else -> UpdateArch.X64
    }
