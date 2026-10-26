package com.pusios.pots.data

import kotlinx.coroutines.flow.StateFlow

interface VectorizationManager {
    val isModelReady: StateFlow<Boolean>
    val isIndexing: StateFlow<Boolean>
    val progress: StateFlow<Float?>
    val status: StateFlow<String>

    val indexedPhotoCount: StateFlow<Int>
    val indexedDocumentCount: StateFlow<Int>
    val currentTotalCount: StateFlow<Int>
    val currentProcessedCount: StateFlow<Int>

    val autoIndexPhotos: StateFlow<Boolean>
    val autoIndexDocuments: StateFlow<Boolean>

    val needsPhotoPermission: StateFlow<Boolean>
    val needsDocumentPermission: StateFlow<Boolean>
    val requiredPhotoPermission: String
    val requiredDocumentPermission: String

    /** True when the on-disk index is incompatible with the current model/prompt/schema. */
    val indexStale: StateFlow<Boolean>

    /** Epoch millis of the last successful indexing run, or null if never indexed. */
    val indexedAt: StateFlow<Long?>

    /** Number of photos skipped during the last indexing run (failures). */
    val skippedPhotoCount: StateFlow<Int>

    /** First error message captured during the last indexing run, if any. */
    val firstIndexError: StateFlow<String?>

    fun setAutoIndexPhotos(enabled: Boolean)
    fun setAutoIndexDocuments(enabled: Boolean)

    fun downloadModel()
    fun cancelDownload()

    suspend fun indexPhotos()
    suspend fun indexDocuments()

    fun cancelIndexing()

    suspend fun search(
        query: String,
        source: String? = null,
        limit: Int = 10,
        minScore: Double = 0.0,
    ): List<Map<String, Any?>>
}
