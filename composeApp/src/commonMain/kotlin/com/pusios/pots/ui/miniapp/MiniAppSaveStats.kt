package com.pusios.pots.ui.miniapp

import com.pusios.pots.data.miniapp.MiniAppRepository

/**
 * A preview of everything a save will write: the screens plus the app's
 * backend (declared collections, staged records and files). Shown in the save
 * dialog so the user sees what gets linked to the app before confirming.
 */
data class MiniAppSaveStats(
    val screens: Int,
    val collections: Int,
    val records: Int,
    val files: Int,
) {
    /** True when the save carries data beyond the screens themselves. */
    val hasBackend: Boolean
        get() = collections > 0 || records > 0 || files > 0
}

/**
 * Counts what saving the current draft would persist.
 *
 * The draft id and the conversation id can each carry declared collections,
 * staged records and files; saving merges the two sources, so the preview
 * merges them the same way and de-duplicates like the save does.
 */
fun MiniAppRepository.saveStats(
    draftId: String,
    conversationId: String?,
    screenCount: Int,
): MiniAppSaveStats {
    val ids = listOfNotNull(draftId, conversationId).distinct()
    val collections = ids.flatMap { draftMeta(it).collections }.distinctBy { it.name }.size
    val records = ids.flatMap { allRecords(it) }.distinctBy { it.collection to it.recordId }.size
    val files = ids.flatMap { files(it) }.distinctBy { it.path }.size
    return MiniAppSaveStats(
        screens = screenCount,
        collections = collections,
        records = records,
        files = files,
    )
}
