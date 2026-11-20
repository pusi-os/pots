package com.pusios.pots.overlay

import android.content.Context
import com.pusios.pots.ui.potsik.PotsikShot
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import java.io.File

/**
 * The frame of the screen as a chat attachment.
 *
 * The chat's own pipeline takes `PlatformFile`s — the same type the picker and
 * the camera hand it — so the screenshot is written to the cache and goes
 * through the very same classify/compress/attach path. No second message format
 * for the screen.
 *
 * It lives in androidMain and not next to `PotsikShot` on purpose: the file, the
 * cache directory and `PlatformFile(AndroidFile.…)` are all Android's, and
 * commonMain is the layer the cloud's rules are tested in without a device.
 *
 * Null means the frame could not be handed over at all. Every caller treats that
 * as "send nothing and say so", never as "send the question without the picture".
 */
internal fun potsikShotFile(context: Context, shot: PotsikShot): PlatformFile? = runCatching {
    val dir = File(context.cacheDir, "potsik").apply { mkdirs() }
    val file = File(dir, "screenshot-${shot.width}x${shot.height}.png")
    file.writeBytes(shot.png)
    PlatformFile(AndroidFile.FileWrapper(file))
}.getOrNull()
