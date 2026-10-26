package com.pusios.pots.tools

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.pusios.pots.data.AppSettings
import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.tool_obsidian_list_description
import pots.composeapp.generated.resources.tool_obsidian_list_name
import pots.composeapp.generated.resources.tool_obsidian_read_description
import pots.composeapp.generated.resources.tool_obsidian_read_name
import pots.composeapp.generated.resources.tool_obsidian_search_description
import pots.composeapp.generated.resources.tool_obsidian_search_name
import pots.composeapp.generated.resources.tool_obsidian_write_description
import pots.composeapp.generated.resources.tool_obsidian_write_name
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.Normalizer
import kotlin.math.sqrt

private const val MAX_VAULT_FILES = 1000
private const val MAX_FILE_BYTES = 2 * 1024 * 1024
private const val MAX_READ_BYTES = 256 * 1024
private const val MAX_SEARCH_CHUNKS = 4000

private data class VaultFile(
    val path: String,
    val document: DocumentFile,
)

private fun vaultRoot(context: Context, appSettings: AppSettings): DocumentFile? {
    val uri = appSettings.getObsidianVaultUri()
    if (uri.isBlank()) return null
    return runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri)) }.getOrNull()
}

private fun listVaultFiles(root: DocumentFile): List<VaultFile> {
    val result = mutableListOf<VaultFile>()
    fun walk(dir: DocumentFile, prefix: String, depth: Int) {
        if (depth > 8 || result.size >= MAX_VAULT_FILES) return
        val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
        for (child in children) {
            if (result.size >= MAX_VAULT_FILES) return
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                walk(child, path, depth + 1)
            } else if (child.isFile && isTextNote(name)) {
                result += VaultFile(path, child)
            }
        }
    }
    walk(root, "", 0)
    return result
}

private fun isTextNote(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")
}

private fun resolvePath(root: DocumentFile, path: String): DocumentFile? {
    val parts = path.split('/').filter { it.isNotBlank() && it != "." }
    if (parts.any { it == ".." }) return null
    var current = root
    for (part in parts) {
        current = current.findFile(part) ?: return null
    }
    return current
}

private fun readText(context: Context, document: DocumentFile, maxBytes: Int = MAX_READ_BYTES): String? {
    if (document.length() > maxBytes) return null
    return runCatching {
        context.contentResolver.openInputStream(document.uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }
    }.getOrNull()
}

private fun tokenize(text: String): List<String> = Normalizer.normalize(text, Normalizer.Form.NFKC)
    .lowercase()
    .split(Regex("[^\\p{L}\\p{N}_]+"))
    .filter { it.length >= 2 && it !in STOP_WORDS }

private val STOP_WORDS = setOf(
    "the", "and", "for", "with", "that", "this", "from", "are", "was", "were",
    "have", "has", "had", "not", "but", "you", "your", "into", "about", "all",
    "can", "will", "just", "its", "also", "than", "then", "them", "they",
    "the", "und", "der", "die", "das", "ein", "eine", "ist", "sind", "mit",
)

private data class Chunk(
    val path: String,
    val text: String,
    val vector: Map<String, Double>,
)

private fun chunkText(text: String, maxChars: Int = 900): List<String> {
    val paragraphs = text.split(Regex("\n\\s*\n"))
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    for (paragraph in paragraphs) {
        if (current.length + paragraph.length > maxChars && current.isNotEmpty()) {
            chunks += current.toString().trim()
            current.clear()
        }
        if (paragraph.length > maxChars) {
            if (current.isNotEmpty()) {
                chunks += current.toString().trim()
                current.clear()
            }
            paragraph.chunked(maxChars).forEach { chunks += it.trim() }
        } else {
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
        }
    }
    if (current.isNotEmpty()) chunks += current.toString().trim()
    return chunks.filter { it.isNotBlank() }
}

object ObsidianListNotesTool {

    const val ID = "obsidian_list_notes"

    val toolInfo = ToolInfo(
        id = ID,
        name = "List Obsidian Notes",
        description = "List markdown notes in the configured Obsidian vault",
        nameRes = Res.string.tool_obsidian_list_name,
        descriptionRes = Res.string.tool_obsidian_list_description,
    )

    fun create(context: Context, appSettings: AppSettings): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "List the notes in the user's Obsidian vault. Configure the vault folder in " +
                "Settings first. Returns relative paths, sizes and modification times.",
            parameters = mapOf(
                "path" to ParameterSchema("string", "Optional subfolder relative to the vault root", false),
                "limit" to ParameterSchema("integer", "Maximum notes to return (default 200)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val root = vaultRoot(context, appSettings)
                ?: return mapOf("success" to false, "error" to "Obsidian vault is not configured. Pick a vault folder in Settings → Tools.")
            val subPath = (args["path"] as? String)?.trim()?.trim('/')
            val base = if (subPath.isNullOrBlank()) {
                root
            } else {
                resolvePath(root, subPath)
                    ?: return mapOf("success" to false, "error" to "Folder not found: $subPath")
            }
            val limit = ((args["limit"] as? Number)?.toInt() ?: 200).coerceIn(1, 1000)
            val files = listVaultFiles(base).take(limit)
            return mapOf(
                "success" to true,
                "vault" to (root.name ?: "vault"),
                "count" to files.size,
                "notes" to files.map { vf ->
                    mapOf(
                        "path" to vf.path,
                        "name" to (vf.document.name ?: vf.path.substringAfterLast('/')),
                        "size_bytes" to vf.document.length(),
                        "last_modified" to vf.document.lastModified(),
                    )
                },
            )
        }
    }
}

object ObsidianReadNoteTool {

    const val ID = "obsidian_read_note"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Read Obsidian Note",
        description = "Read a note from the configured Obsidian vault",
        nameRes = Res.string.tool_obsidian_read_name,
        descriptionRes = Res.string.tool_obsidian_read_description,
    )

    fun create(context: Context, appSettings: AppSettings): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Read a note from the Obsidian vault by its relative path. Large notes are " +
                "truncated.",
            parameters = mapOf(
                "path" to ParameterSchema("string", "Relative path, e.g. Notes/idea.md", true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val path = (args["path"] as? String)?.trim()
                ?: return mapOf("success" to false, "error" to "path is required")
            val root = vaultRoot(context, appSettings)
                ?: return mapOf("success" to false, "error" to "Obsidian vault is not configured.")
            val document = resolvePath(root, path)
                ?: return mapOf("success" to false, "error" to "Note not found: $path")
            if (!document.isFile) {
                return mapOf("success" to false, "error" to "Not a file: $path")
            }
            val content = readText(context, document)
                ?: return mapOf("success" to false, "error" to "Could not read $path (too large or unreadable).")
            return mapOf(
                "success" to true,
                "path" to path,
                "size_bytes" to document.length(),
                "content" to content,
            )
        }
    }
}

object ObsidianWriteNoteTool {

    const val ID = "obsidian_write_note"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Write Obsidian Note",
        description = "Create or update a note in the configured Obsidian vault",
        nameRes = Res.string.tool_obsidian_write_name,
        descriptionRes = Res.string.tool_obsidian_write_description,
    )

    fun create(context: Context, appSettings: AppSettings): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Create or overwrite a note in the Obsidian vault. Parent folders are " +
                "created automatically. Confirm destructive overwrites with the user first.",
            parameters = mapOf(
                "path" to ParameterSchema("string", "Relative path, e.g. Pots/summary.md", true),
                "content" to ParameterSchema("string", "Full note content (Markdown)", true),
                "append" to ParameterSchema("boolean", "Append to an existing note instead of overwriting", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val path = (args["path"] as? String)?.trim()
                ?: return mapOf("success" to false, "error" to "path is required")
            val content = args["content"] as? String
                ?: return mapOf("success" to false, "error" to "content is required")
            val append = args["append"] as? Boolean ?: false
            val root = vaultRoot(context, appSettings)
                ?: return mapOf("success" to false, "error" to "Obsidian vault is not configured.")
            val parts = path.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty() || parts.any { it == ".." || it == "." }) {
                return mapOf("success" to false, "error" to "Invalid path: $path")
            }

            var current = root
            for (part in parts.dropLast(1)) {
                current = current.findFile(part) ?: current.createDirectory(part)
                    ?: return mapOf("success" to false, "error" to "Could not create folder: $part")
            }
            val fileName = parts.last()
            var document = current.findFile(fileName)
            if (document == null) {
                document = current.createFile(mimeTypeFor(fileName), fileName)
                    ?: return mapOf("success" to false, "error" to "Could not create note: $fileName")
            }
            if (!document.isFile) {
                return mapOf("success" to false, "error" to "Not a file: $path")
            }

            return try {
                context.contentResolver.openOutputStream(document.uri, if (append) "wa" else "w")?.use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                        writer.write(content)
                        if (append && !content.endsWith("\n")) writer.write("\n")
                    }
                } ?: return mapOf("success" to false, "error" to "Could not open note for writing.")
                mapOf(
                    "success" to true,
                    "path" to path,
                    "uri" to document.uri.toString(),
                    "size_bytes" to document.length(),
                )
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Could not write note: ${e.message}")
            }
        }
    }
}

private fun mimeTypeFor(name: String): String = when {
    name.endsWith(".md", ignoreCase = true) || name.endsWith(".markdown", ignoreCase = true) -> "text/markdown"
    name.endsWith(".txt", ignoreCase = true) -> "text/plain"
    else -> "text/plain"
}

object ObsidianSearchNotesTool {

    const val ID = "obsidian_search_notes"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Search Obsidian Notes",
        description = "Search the Obsidian vault with local vector similarity",
        nameRes = Res.string.tool_obsidian_search_name,
        descriptionRes = Res.string.tool_obsidian_search_description,
    )

    fun create(context: Context, appSettings: AppSettings): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Search the Obsidian vault using a local TF-IDF vector index. Returns the " +
                "most relevant note chunks with paths and snippets. No network or cloud is used.",
            parameters = mapOf(
                "query" to ParameterSchema("string", "Search query", true),
                "limit" to ParameterSchema("integer", "Maximum chunks to return (default 8)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = (args["query"] as? String)?.trim()
                ?: return mapOf("success" to false, "error" to "query is required")
            val limit = ((args["limit"] as? Number)?.toInt() ?: 8).coerceIn(1, 30)
            val root = vaultRoot(context, appSettings)
                ?: return mapOf("success" to false, "error" to "Obsidian vault is not configured.")
            val files = listVaultFiles(root)
            if (files.isEmpty()) {
                return mapOf("success" to true, "count" to 0, "results" to emptyList<Any>())
            }

            val queryTokens = tokenize(query)
            if (queryTokens.isEmpty()) {
                return mapOf("success" to false, "error" to "Query has no searchable terms.")
            }

            val chunks = mutableListOf<Chunk>()
            var totalChunks = 0
            for (file in files) {
                if (totalChunks >= MAX_SEARCH_CHUNKS) break
                val text = readText(context, file.document) ?: continue
                for (chunkText in chunkText(text)) {
                    if (totalChunks >= MAX_SEARCH_CHUNKS) break
                    val tokens = tokenize(chunkText)
                    if (tokens.isEmpty()) continue
                    val tf = termFrequencies(tokens)
                    chunks += Chunk(file.path, chunkText, tf)
                    totalChunks++
                }
            }
            if (chunks.isEmpty()) {
                return mapOf("success" to true, "count" to 0, "results" to emptyList<Any>())
            }

            val idf = inverseDocumentFrequencies(chunks.map { it.vector.keys })
            val queryVector = tfidfVector(queryTokens, idf)
            val queryNorm = norm(queryVector)
            if (queryNorm == 0.0) {
                return mapOf("success" to true, "count" to 0, "results" to emptyList<Any>())
            }

            val scored = chunks.map { chunk ->
                val vector = chunk.vector.mapValues { (term, tf) -> tf * (idf[term] ?: 0.0) }
                val score = cosine(queryVector, vector, queryNorm)
                chunk to score
            }.filter { it.second > 0.0 }
                .sortedByDescending { it.second }
                .take(limit)

            return mapOf(
                "success" to true,
                "indexed_files" to files.size,
                "indexed_chunks" to chunks.size,
                "count" to scored.size,
                "results" to scored.map { (chunk, score) ->
                    mapOf(
                        "path" to chunk.path,
                        "score" to (score * 1000).toInt() / 1000.0,
                        "snippet" to chunk.text.take(700),
                    )
                },
            )
        }
    }
}

private fun termFrequencies(tokens: List<String>): Map<String, Double> {
    val counts = tokens.groupingBy { it }.eachCount()
    val total = tokens.size.toDouble().coerceAtLeast(1.0)
    return counts.mapValues { it.value / total }
}

private fun inverseDocumentFrequencies(documentTerms: List<Set<String>>): Map<String, Double> {
    val documentCount = documentTerms.size.toDouble().coerceAtLeast(1.0)
    val df = mutableMapOf<String, Int>()
    for (terms in documentTerms) {
        for (term in terms) df[term] = df.getOrElse(term) { 0 } + 1
    }
    return df.mapValues { (_, count) -> kotlin.math.ln((documentCount + 1.0) / (count + 1.0)) + 1.0 }
}

private fun tfidfVector(tokens: List<String>, idf: Map<String, Double>): Map<String, Double> {
    val tf = termFrequencies(tokens)
    return tf.mapValues { (term, value) -> value * (idf[term] ?: 0.0) }
}

private fun norm(vector: Map<String, Double>): Double = sqrt(vector.values.sumOf { it * it })

private fun cosine(a: Map<String, Double>, b: Map<String, Double>, aNorm: Double): Double {
    if (aNorm == 0.0) return 0.0
    var dot = 0.0
    for ((term, value) in a) {
        b[term]?.let { dot += value * it }
    }
    val bNorm = norm(b)
    if (bNorm == 0.0) return 0.0
    return dot / (aNorm * bNorm)
}
