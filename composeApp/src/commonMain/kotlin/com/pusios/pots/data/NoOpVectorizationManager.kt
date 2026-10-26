package com.pusios.pots.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NoOpVectorizationManager : VectorizationManager {
    override val isModelReady: StateFlow<Boolean> = MutableStateFlow(false)
    override val isIndexing: StateFlow<Boolean> = MutableStateFlow(false)
    override val progress: StateFlow<Float?> = MutableStateFlow(null)
    override val status: StateFlow<String> = MutableStateFlow("")

    override val indexedPhotoCount: StateFlow<Int> = MutableStateFlow(0)
    override val indexedDocumentCount: StateFlow<Int> = MutableStateFlow(0)
    override val currentTotalCount: StateFlow<Int> = MutableStateFlow(0)
    override val currentProcessedCount: StateFlow<Int> = MutableStateFlow(0)

    override val indexStale: StateFlow<Boolean> = MutableStateFlow(false)
    override val indexedAt: StateFlow<Long?> = MutableStateFlow(null)
    override val skippedPhotoCount: StateFlow<Int> = MutableStateFlow(0)
    override val firstIndexError: StateFlow<String?> = MutableStateFlow(null)

    override val autoIndexPhotos: StateFlow<Boolean> = MutableStateFlow(false)
    override val autoIndexDocuments: StateFlow<Boolean> = MutableStateFlow(false)

    override val needsPhotoPermission: StateFlow<Boolean> = MutableStateFlow(false)
    override val needsDocumentPermission: StateFlow<Boolean> = MutableStateFlow(false)
    override val requiredPhotoPermission: String = ""
    override val requiredDocumentPermission: String = ""

    override fun setAutoIndexPhotos(enabled: Boolean) {}
    override fun setAutoIndexDocuments(enabled: Boolean) {}

    override fun downloadModel() {}
    override fun cancelDownload() {}

    override suspend fun indexPhotos() {}
    override suspend fun indexDocuments() {}

    override fun cancelIndexing() {}

    override suspend fun search(query: String, source: String?, limit: Int, minScore: Double): List<Map<String, Any?>> = emptyList()
}
