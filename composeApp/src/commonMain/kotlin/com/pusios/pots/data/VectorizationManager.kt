package com.pusios.pots.data

import kotlinx.coroutines.flow.StateFlow

interface VectorizationManager {
    val isModelReady: StateFlow<Boolean>
    val isIndexing: StateFlow<Boolean>
    val progress: StateFlow<Float?>
    val status: StateFlow<String>

    val autoIndexPhotos: StateFlow<Boolean>
    val autoIndexDocuments: StateFlow<Boolean>

    fun setAutoIndexPhotos(enabled: Boolean)
    fun setAutoIndexDocuments(enabled: Boolean)

    fun downloadModel()
    fun cancelDownload()

    suspend fun indexPhotos()
    suspend fun indexDocuments()

    suspend fun search(query: String, source: String? = null, limit: Int = 10): List<Map<String, Any?>>
}
