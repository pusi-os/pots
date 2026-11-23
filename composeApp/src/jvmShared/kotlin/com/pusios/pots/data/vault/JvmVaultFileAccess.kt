// Vault file IO for the JVM targets (android + desktop). Writes go to a temp
// file and are moved into place atomically, so a crash mid-write can never
// leave a half-written vault behind; an unreadable file is renamed aside, not
// overwritten.

package com.pusios.pots.data.vault

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class JvmVaultFileAccess(
    private val directory: File,
    private val fileName: String = VaultFormat.FILE_NAME,
) : VaultFileAccess {

    private val file: File
        get() = File(directory, fileName)

    override fun exists(): Boolean = file.isFile

    override fun read(): ByteArray? = try {
        file.takeIf { it.isFile }?.readBytes()
    } catch (_: Exception) {
        null
    }

    override fun writeAtomic(bytes: ByteArray): Boolean = try {
        directory.mkdirs()
        val temp = File(directory, "$fileName.tmp")
        temp.writeBytes(bytes)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (_: Exception) {
        false
    }

    override fun delete(): Boolean = try {
        file.delete()
    } catch (_: Exception) {
        false
    }

    override fun quarantineUnreadable(): String? = try {
        if (!file.isFile) {
            null
        } else {
            val name = "$fileName.unreadable-${System.currentTimeMillis()}"
            if (file.renameTo(File(directory, name))) name else null
        }
    } catch (_: Exception) {
        null
    }
}
