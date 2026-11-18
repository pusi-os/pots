package com.pusios.pots.update

/**
 * A sequential writer for one file under the app files directory.
 *
 * The update is streamed to disk chunk by chunk: an APK is a quarter of a gigabyte, so it
 * must never be assembled in memory first.
 */
interface UpdateFileSink {
    /** Appends [length] bytes of [buffer]; false when the write failed. */
    fun write(buffer: ByteArray, length: Int): Boolean

    /** Flushes and closes the file; false when the last flush failed. */
    fun close(): Boolean

    /** Removes the partial file. Safe to call after [close]. */
    fun delete()

    /** Free bytes on the volume this file lives on, or -1 when the platform cannot tell. */
    fun freeSpaceBytes(): Long
}

/**
 * Opens `<app files>/updates/<fileName>` for writing, or null where Pots cannot self-update:
 * iOS installs through the App Store and the web build has no filesystem.
 */
expect fun openUpdateFileSink(fileName: String): UpdateFileSink?

/** CPU family of this device, so a Linux host never gets the tarball of another architecture. */
expect val hostArchitecture: UpdateArch

/**
 * Hands a downloaded update to the platform installer: the APK package installer on Android,
 * the OS handler for `.dmg`/`.msi`/`.deb`/`.rpm`/`.AppImage` on desktop.
 *
 * False means nothing was launched — the UI then points the user at the saved file, since a
 * missing handler is not an error the app can fix.
 */
expect fun openDownloadedUpdate(fileName: String): Boolean
