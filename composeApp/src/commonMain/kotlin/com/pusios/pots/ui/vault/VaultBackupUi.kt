// Encrypted vault: export/import file pickers (card 5/5). The dialogs are
// shared (commonMain); only the file chooser is platform-specific — a save/open
// Swing dialog on desktop, SAF documents on Android. iOS and web have no vault
// in v1, so their buttons never render (the manager screen is Unavailable).
//
// `produceBytes`/`onLoaded` run in a coroutine after the user picked a file:
// sealing the export (PBKDF2 600k) must not block the UI thread.

package com.pusios.pots.ui.vault

import androidx.compose.runtime.Composable

/** Destination for an exported *.pvault file (a picked path or SAF stream). */
interface VaultExportSink {
    /** Write the whole file; false when the write failed. */
    fun write(bytes: ByteArray): Boolean
}

/** A *.pvault file picked for import; null bytes mean the read failed. */
interface VaultImportSource {
    fun read(): ByteArray?
}

/**
 * How the export file step ended.
 */
enum class VaultExportOutcome {
    /** The file was written to the picked destination. */
    WRITTEN,

    /** The user cancelled the file picker; the dialog stays open. */
    CANCELLED,

    /** The pick succeeded but writing (or producing) the bytes failed. */
    FAILED,
}

/**
 * The file-saving step of the export dialog. [produceBytes] is invoked only
 * after the user picked a destination; [onDone] reports the outcome — picking
 * nothing is a [VaultExportOutcome.CANCELLED], not a write failure.
 * [enabled] mirrors the dialog's form validation.
 */
@Composable
expect fun VaultExportPickButton(
    label: String,
    enabled: Boolean,
    fileName: String,
    produceBytes: suspend () -> ByteArray,
    onDone: (VaultExportOutcome) -> Unit,
)

/**
 * The file-picking step of the import dialog. [onLoaded] receives the picked
 * source, or null when the user cancelled.
 */
@Composable
expect fun VaultImportPickButton(
    label: String,
    enabled: Boolean,
    onLoaded: (VaultImportSource?) -> Unit,
)
