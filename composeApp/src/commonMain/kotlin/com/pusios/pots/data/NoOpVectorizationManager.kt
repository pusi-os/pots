package com.pusios.pots.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NoOpVectorizationManager : VectorizationManager {
    override val isModelReady: StateFlow<Boolean> = MutableStateFlow(false)
    override val isIndexing: StateFlow<Boolean> = MutableStateFlow(false)
    override val progress: StateFlow<Float?> = MutableStateFlow(null)
    override val status: StateFlow<String> = MutableStateFlow("")

    override val autoIndexPhotos: StateFlow<Boolean> = MutableStateFlow(false)
    override val autoIndexDocuments: StateFlow<Boolean> = MutableStateFlow(false)

    override fun setAutoIndexPhotos(enabled: Boolean) {}
    override fun setAutoIndexDocuments(enabled: Boolean) {}

    override fun downloadModel() {}
    override fun cancelDownload() {}

    override suspend fun indexPhotos() {}
    override suspend fun indexDocuments() {}

    override suspend fun search(query: String, source: String?, limit: Int): List<Map<String, Any?>> = emptyList()
}
