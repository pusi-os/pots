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
                    "Returns the most relevant items with scores.",
                parameters = mapOf(
                    "query" to ParameterSchema("string", "Search query", true),
                    "source" to ParameterSchema("string", "Optional source: photo or document", false),
                    "limit" to ParameterSchema("integer", "Maximum results (default 10)", false),
                ),
            )

            override suspend fun execute(args: Map<String, Any>): Any {
                val query = (args["query"] as? String)?.trim()
                    ?: return mapOf("success" to false, "error" to "query is required")
                val source = (args["source"] as? String)?.trim()?.ifBlank { null }
                val limit = ((args["limit"] as? Number)?.toInt() ?: 10).coerceIn(1, 50)
                return try {
                    val results = manager.search(query, source, limit)
                    mapOf("success" to true, "count" to results.size, "results" to results)
                } catch (e: Exception) {
                    mapOf("success" to false, "error" to "Vector search failed: ${e.message}")
                }
            }
        }
    }
}
