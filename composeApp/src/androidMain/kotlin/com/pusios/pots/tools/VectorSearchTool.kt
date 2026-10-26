package com.pusios.pots.tools

import android.content.Context
import com.pusios.pots.data.VectorizationManager
import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema
import org.koin.java.KoinJavaComponent

object VectorSearchTool {

    const val ID = "vector_search_storage"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Vector Search Storage",
        description = "Semantic search over indexed photos and documents",
    )

    fun create(context: Context): Tool {
        val manager: VectorizationManager = KoinJavaComponent.get(VectorizationManager::class.java)
        return object : Tool {
            override val schema = ToolSchema(
                name = ID,
                description = "Semantic search over the on-device vector index of photos and documents. " +
                    "Use this tool FIRST for any request about the user's photos or documents, including by date, object, scene, or topic. " +
                    "Do not use shell, Python, or manual file scanning. Returns URIs that can be shown as images.",
                parameters = mapOf(
                    "query" to ParameterSchema("string", "Search query", true),
                    "source" to ParameterSchema("string", "Optional source: photo or document", false),
                    "limit" to ParameterSchema("integer", "Maximum results (default 10)", false),
                    "minScore" to ParameterSchema("number", "Minimum cosine similarity (default 0.0)", false),
                ),
            )

            override suspend fun execute(args: Map<String, Any>): Any {
                val query = (args["query"] as? String)?.trim()
                    ?: return mapOf("success" to false, "error" to "query is required")
                val source = (args["source"] as? String)?.trim()?.ifBlank { null }
                val limit = ((args["limit"] as? Number)?.toInt() ?: 10).coerceIn(1, 50)
                val minScore = (args["minScore"] as? Number)?.toDouble() ?: 0.0
                if (manager.indexStale.value) {
                    return mapOf(
                        "success" to true,
                        "count" to 0,
                        "results" to emptyList<Any>(),
                        "index" to indexInfo(manager),
                        "scanned" to 0,
                        "hint" to "индекс устарел, нужна переиндексация",
                    )
                }
                return try {
                    val results = manager.search(query, source, limit, minScore)
                    val payload = mutableMapOf<String, Any>(
                        "success" to true,
                        "count" to results.size,
                        "results" to results,
                        "index" to indexInfo(manager),
                    )
                    if (results.isEmpty()) {
                        payload["hint"] = "ничего не найдено; индекс: ${manager.indexedPhotoCount.value} фото, модель ok"
                    }
                    payload
                } catch (e: Exception) {
                    mapOf("success" to false, "error" to "Vector search failed: ${e.message}")
                }
            }
        }
    }

    private fun indexInfo(manager: VectorizationManager): Map<String, Any?> = mapOf(
        "photos" to manager.indexedPhotoCount.value,
        "documents" to manager.indexedDocumentCount.value,
        "modelId" to "embeddinggemma-2-text-vision-440m",
        "stale" to manager.indexStale.value,
        "indexedAt" to manager.indexedAt.value,
        "skipped" to manager.skippedPhotoCount.value,
        "firstError" to manager.firstIndexError.value,
    )
}
