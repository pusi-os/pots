package com.pusios.pots.ui.settings

import androidx.compose.runtime.Immutable
import com.pusios.pots.update.DownloadProgress
import com.pusios.pots.update.UpdateError

/** The steps the self-update walks through; the settings section renders the current one. */
enum class UpdatePhase {
    /** Nothing started yet — only the button. */
    Idle,
    Checking,
    Downloading,

    /** Archive containers only: the header is being validated before the OS gets the file. */
    Unpacking,
    Installing,

    /** The file is on disk; installing it needs the user and a restart. */
    Done,
    UpToDate,
    Failed,
}

@Immutable
data class AppUpdateUiState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    /** False on iOS and web, where Pots cannot install its own build. */
    val isSupported: Boolean = true,
    /** Version of the release the check found. */
    val versionName: String? = null,
    val progress: DownloadProgress? = null,
    val error: UpdateError? = null,
    /** Name of the downloaded file under `updates/` in the app files directory. */
    val fileName: String? = null,
    /** True when the platform installer was launched, false when the user must install by hand. */
    val installerOpened: Boolean = false,
) {
    val isBusy: Boolean
        get() = phase == UpdatePhase.Checking ||
            phase == UpdatePhase.Downloading ||
            phase == UpdatePhase.Unpacking ||
            phase == UpdatePhase.Installing
}
