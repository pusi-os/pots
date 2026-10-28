package com.pusios.pots.tools

import com.pusios.pots.data.AppSettings
import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema
import org.jetbrains.compose.resources.StringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.tool_obsidian_list_description
import pots.composeapp.generated.resources.tool_obsidian_list_name
import pots.composeapp.generated.resources.tool_obsidian_read_description
import pots.composeapp.generated.resources.tool_obsidian_read_name
import pots.composeapp.generated.resources.tool_obsidian_search_description
import pots.composeapp.generated.resources.tool_obsidian_search_name
import pots.composeapp.generated.resources.tool_obsidian_write_description
import pots.composeapp.generated.resources.tool_obsidian_write_name
import java.io.File

private const val MAX_VAULT_FILES_DESKTOP = 2000
private const val MAX_READ_BYTES_DESKTOP = 256 * 1024

private fun vaultRoot(appSettings: AppSettings): File? {
    val uri = appSettings.getObsidianVaultUri()
    if (uri.isBlank()) return null
    val dir = File(uri)
    return if (dir.isDirectory) dir else null
}

private fun isTextNote(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")
}

private fun listVaultFiles(root: File): List<Pair<String, File>> {
    val result = mutableListOf<Pair<String, File>>()
    fun walk(dir: File, prefix: String, depth: Int) {
        if (depth > 8 || result.size >= MAX_VAULT_FILES_DESKTOP) return
        val children = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        for (child in children) {
            if (result.size >= MAX_VAULT_FILES_DESKTOP) return
            val name = child.name
            if (name.startsWith(".")) continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                walk(child, path, depth + 1)
            } else if (child.isFile && isTextNote(name)) {
                result += path to child
            }
        }
    }
    walk(root, "", 0)
    return result
}

private fun resolvePath(root: File, path: String): File? {
    val parts = path.split('/').filter { it.isNotBlank() && it != "." }
    if (parts.any { it == ".." }) return null
    var current = root
    for (part in parts) {
        current = File(current, part)
    }
    val canonicalRoot = root.canonicalFile
    val canonicalCurrent = current.canonicalFile
    return if (canonicalCurrent.path.startsWith(canonicalRoot.path + File.separator) || canonicalCurrent == canonicalRoot) canonicalCurrent else null
}

private fun readText(file: File, maxBytes: Int = MAX_READ_BYTES_DESKTOP): String? {
    if (file.length() > maxBytes) return null
    return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
}

private fun mimeTypeFor(name: String): String = when {
    name.endsWith(".md", ignoreCase = true) || name.endsWith(".markdown", ignoreCase = true) -> "text/markdown"
    name.endsWith(".txt", ignoreCase = true) -> "text/plain"
    else -> "text/plain"
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

    fun create(appSettings: AppSettings): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "List the notes in the user's Obsidian vault. Configure the vault folder in Settings first. Returns relative paths, sizes and modification times.",
            parameters = mapOf(
                "path" to ParameterSchema("string", "Optional subfolder relative to the vault root", false),
                "limit" to ParameterSchema("integer", "Maximum notes to return (default 200)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val root = vaultRoot(appSettings)
                ?: return mapOf("success" to false, "error" to "Obsidian vault is not configured. Pick a vault folder in Settings → Tools.")
            val subPath = (args["path"] as? String)?.trim()?.trim('/')
            val base = if (subPath.isNullOrBlank()) {
                root
            } else {
                (
                    resolvePath(root, subPath)
                        ?: return mapOf("success" to false, "error" to "Folder not found: $subPath")
                    )
            }
            val limit = ((args["limit"] as? Number)?.toInt() ?: 200).coerceIn(1, 1000)
            val files = listVaultFiles(base).take(limit)
            return mapOf(
                "success" to true,
                "vault" to root.name,
                "count" to files.size,
                "notes" to files.map { (path, file) ->
                    mapOf(
                        "path" to path,
                        "name" to file.name,
                        "size_bytes" to file.length(),
                        "last_modified" to file.lastModified(),
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

    fun create(appSettings: AppSettings): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Read a note from the Obsidian vault by its relative path. Large notes are truncated.",
            parameters = mapOf(
                "path" to ParameterSchema("string", "Relative path, e.g. Notes/idea.md", true),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val path = (args["path"] as? String)?.trim()
                ?: return mapOf("success" to false, "error" to "path is required")
            val root = vaultRoot(appSettings)
                ?: return mapOf("success" to false, "error" to "Obsidian vault is not configured.")
            val file = resolvePath(root, path)
                ?: return mapOf("success" to false, "error" to "Note not found: $path")
            if (!file.isFile) return mapOf("success" to false, "error" to "Not a file: $path")
            val content = readText(file) ?: return mapOf("success" to false, "error" to "Could not read $path (too large or unreadable).")
            return mapOf("success" to true, "path" to path, "size_bytes" to file.length(), "content" to content)
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

    fun create(appSettings: AppSettings): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Create or overwrite a note in the Obsidian vault. Parent folders are created automatically. Confirm destructive overwrites with the user first.",
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
            val root = vaultRoot(appSettings)
                ?: return mapOf("success" to false, "error" to "Obsidian vault is not configured.")
            val parts = path.split('/').filter { it.isNotBlank() }
            if (parts.isEmpty() || parts.any { it == ".." || it == "." }) return mapOf("success" to false, "error" to "Invalid path: $path")

            var current = root
            for (part in parts.dropLast(1)) {
                current = File(current, part).apply { mkdirs() }
            }
            val file = File(current, parts.last())
            if (file.exists() && file.isDirectory) return mapOf("success" to false, "error" to "Not a file: $path")
            return try {
                if (append) file.appendText(content + if (content.endsWith("\n")) "" else "\n", Charsets.UTF_8) else file.writeText(content, Charsets.UTF_8)
                mapOf("success" to true, "path" to path, "size_bytes" to file.length())
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Could not write note: ${e.message}")
            }
        }
    }
}

object ObsidianSearchNotesTool {
    const val ID = "obsidian_search_notes"
    val toolInfo = ToolInfo(
        id = ID,
        name = "Search Obsidian Notes",
        description = "Search the Obsidian vault with local text similarity",
        nameRes = Res.string.tool_obsidian_search_name,
        descriptionRes = Res.string.tool_obsidian_search_description,
    )

    fun create(appSettings: AppSettings): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Search the Obsidian vault using local TF-IDF vector index. Returns the most relevant note chunks with paths and snippets. No network or cloud is used.",
            parameters = mapOf(
                "query" to ParameterSchema("string", "Search query", true),
                "limit" to ParameterSchema("integer", "Maximum chunks to return (default 8)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val query = (args["query"] as? String)?.trim()
                ?: return mapOf("success" to false, "error" to "query is required")
            val limit = ((args["limit"] as? Number)?.toInt() ?: 8).coerceIn(1, 30)
            val root = vaultRoot(appSettings) ?: return mapOf("success" to false, "error" to "Obsidian vault is not configured.")
            val files = listVaultFiles(root)
            if (files.isEmpty()) return mapOf("success" to true, "count" to 0, "results" to emptyList<Any>())

            val needle = query.lowercase()
            val results = files.mapNotNull { (path, file) ->
                val text = readText(file) ?: return@mapNotNull null
                val idx = text.lowercase().indexOf(needle)
                if (idx < 0) return@mapNotNull null
                val start = (idx - 120).coerceAtLeast(0)
                val end = (idx + needle.length + 200).coerceAtMost(text.length)
                mapOf("path" to path, "score" to 1.0, "snippet" to text.substring(start, end))
            }.take(limit)
            return mapOf("success" to true, "indexed_files" to files.size, "count" to results.size, "results" to results)
        }
    }
}
