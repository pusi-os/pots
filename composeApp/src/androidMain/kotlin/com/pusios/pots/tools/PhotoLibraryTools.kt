package com.pusios.pots.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pusios.pots.network.tools.ParameterSchema
import com.pusios.pots.network.tools.Tool
import com.pusios.pots.network.tools.ToolInfo
import com.pusios.pots.network.tools.ToolSchema
import com.pusios.pots.sandbox.LinuxSandboxManager
import com.pusios.pots.sandbox.SandboxState
import com.pusios.pots.sandbox.resolveSandboxFile
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.tool_import_photo_description
import pots.composeapp.generated.resources.tool_import_photo_name
import pots.composeapp.generated.resources.tool_list_downloads_description
import pots.composeapp.generated.resources.tool_list_downloads_name
import pots.composeapp.generated.resources.tool_save_to_downloads_description
import pots.composeapp.generated.resources.tool_save_to_downloads_name
import pots.composeapp.generated.resources.tool_search_photos_description
import pots.composeapp.generated.resources.tool_search_photos_name
import java.io.File
import java.time.Instant

private const val PHOTO_IMPORT_DIR = "imports/photos"
private const val DOWNLOAD_SUBDIR = "Pots"

private data class PhotoRecord(
    val id: Long,
    val name: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAddedSeconds: Long,
    val bucket: String?,
)

private fun queryPhotos(
    context: Context,
    query: String?,
    sinceDays: Int?,
    limit: Int,
): List<PhotoRecord> {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    )

    val selectionParts = mutableListOf<String>()
    val selectionArgs = mutableListOf<String>()
    if (!query.isNullOrBlank()) {
        selectionParts += "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        selectionArgs += "%${query.trim()}%"
    }
    if (sinceDays != null && sinceDays > 0) {
        selectionParts += "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        selectionArgs += (Instant.now().epochSecond - sinceDays * 86_400L).toString()
    }

    val records = mutableListOf<PhotoRecord>()
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
        selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray(),
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
        val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
        val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
        val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val bucketIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

        while (cursor.moveToNext() && records.size < limit) {
            records += PhotoRecord(
                id = cursor.getLong(idIndex),
                name = cursor.getString(nameIndex) ?: "photo_${cursor.getLong(idIndex)}",
                mimeType = cursor.getString(mimeIndex) ?: "image/*",
                width = cursor.getInt(widthIndex),
                height = cursor.getInt(heightIndex),
                sizeBytes = cursor.getLong(sizeIndex),
                dateAddedSeconds = cursor.getLong(dateIndex),
                bucket = cursor.getString(bucketIndex),
            )
        }
    }
    return records
}

private fun PhotoRecord.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "uri" to ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString(),
    "mime_type" to mimeType,
    "width" to width,
    "height" to height,
    "size_bytes" to sizeBytes,
    "date" to Instant.ofEpochSecond(dateAddedSeconds).toString(),
    "album" to bucket,
)

object SearchPhotosTool {

    const val ID = "search_photos"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Search Photos",
        description = "Search the device photo library by filename",
        nameRes = Res.string.tool_search_photos_name,
        descriptionRes = Res.string.tool_search_photos_description,
    )

    fun create(context: Context, permissionController: PermissionController): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Search the user's photo library. Returns metadata and a media id for each " +
                "matching image. Use import_photo to copy one into the sandbox.",
            parameters = mapOf(
                "query" to ParameterSchema("string", "Text matched against the photo filename", false),
                "since_days" to ParameterSchema("integer", "Only include photos added in the last N days", false),
                "limit" to ParameterSchema("integer", "Maximum results, 1-100 (default 20)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (!permissionController.hasPermission()) {
                val granted = permissionController.requestPermission()
                if (!granted) {
                    return mapOf(
                        "success" to false,
                        "error" to "Photo access denied. Enable photo access in Android settings and try again.",
                    )
                }
            }

            val query = (args["query"] as? String)?.trim()
            val sinceDays = (args["since_days"] as? Number)?.toInt()
            val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)

            return try {
                val photos = queryPhotos(context, query, sinceDays, limit)
                mapOf(
                    "success" to true,
                    "count" to photos.size,
                    "photos" to photos.map { it.toMap() },
                )
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Photo search failed: ${e.message}")
            }
        }
    }
}

object ImportPhotoTool {

    const val ID = "import_photo"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Import Photo",
        description = "Copy a photo from the device library into the sandbox",
        nameRes = Res.string.tool_import_photo_name,
        descriptionRes = Res.string.tool_import_photo_description,
    )

    fun create(
        context: Context,
        sandboxManager: LinuxSandboxManager,
        permissionController: PermissionController,
    ): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Copy one photo from the device library into /root/imports/photos so the " +
                "shell and open_file tools can work with it. Get the media id from search_photos.",
            parameters = mapOf(
                "media_id" to ParameterSchema("string", "Media id returned by search_photos", true),
                "filename" to ParameterSchema("string", "Optional filename to save as", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val mediaId = (args["media_id"] as? Number)?.toLong()
                ?: (args["media_id"] as? String)?.toLongOrNull()
                ?: return mapOf("success" to false, "error" to "media_id is required")

            if (sandboxManager.state.value !is SandboxState.Ready) {
                return mapOf("success" to false, "error" to "The Linux sandbox is not installed or not running.")
            }

            if (!permissionController.hasPermission()) {
                val granted = permissionController.requestPermission()
                if (!granted) {
                    return mapOf("success" to false, "error" to "Photo access denied.")
                }
            }

            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
            val record = try {
                queryPhotoById(context, mediaId)
            } catch (e: Exception) {
                null
            } ?: return mapOf("success" to false, "error" to "Photo $mediaId was not found.")

            val requestedName = (args["filename"] as? String)?.substringAfterLast('/')?.substringAfterLast('\\')
            val name = sanitizeFilename(requestedName ?: record.name)
            val dir = File(sandboxManager.homePath, PHOTO_IMPORT_DIR).apply { mkdirs() }
            val target = uniqueFile(dir, name)

            return try {
                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) return mapOf("success" to false, "error" to "Could not open photo $mediaId.")
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                mapOf(
                    "success" to true,
                    "media_id" to mediaId,
                    "path" to "$PHOTO_IMPORT_DIR/${target.name}",
                    "sandbox_path" to "/root/$PHOTO_IMPORT_DIR/${target.name}",
                    "size_bytes" to target.length(),
                    "mime_type" to record.mimeType,
                )
            } catch (e: Exception) {
                target.delete()
                mapOf("success" to false, "error" to "Could not import photo: ${e.message}")
            }
        }
    }
}

private fun queryPhotoById(context: Context, mediaId: Long): PhotoRecord? {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    )
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Images.Media._ID} = ?",
        arrayOf(mediaId.toString()),
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return null
        return PhotoRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)),
            name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: "photo.jpg",
            mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "image/*",
            width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)),
            height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)),
            sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
            dateAddedSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)),
            bucket = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)),
        )
    }
    return null
}

private fun sanitizeFilename(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned = base.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
        .joinToString("")
        .trim('.', ' ')
    return cleaned.ifBlank { "photo.jpg" }
}

private fun uniqueFile(dir: File, name: String): File {
    val direct = File(dir, name)
    if (!direct.exists()) return direct
    val stem = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var index = 1
    while (true) {
        val candidate = File(dir, "${stem}_$index$extension")
        if (!candidate.exists()) return candidate
        index++
    }
}

object SaveToDownloadsTool {

    const val ID = "save_to_downloads"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Save to Downloads",
        description = "Copy a sandbox file into the device Downloads folder",
        nameRes = Res.string.tool_save_to_downloads_name,
        descriptionRes = Res.string.tool_save_to_downloads_description,
    )

    fun create(context: Context, sandboxManager: LinuxSandboxManager): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Copy a file from the sandbox /root into the shared Downloads folder under " +
                "Download/Pots. The user can then open it from Files or another app. Only available " +
                "on Android 10 and newer.",
            parameters = mapOf(
                "path" to ParameterSchema("string", "Sandbox path relative to /root, e.g. report.pdf", true),
                "filename" to ParameterSchema("string", "Optional filename to show in Downloads", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val path = (args["path"] as? String)?.trim()
                ?: return mapOf("success" to false, "error" to "path is required")
            val source = resolveSandboxFile(sandboxManager.homePath, path)
                ?: return mapOf("success" to false, "error" to "Invalid path: must be relative to /root")
            if (!source.isFile) {
                return mapOf("success" to false, "error" to "File not found: $path")
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return mapOf("success" to false, "error" to "Saving to Downloads requires Android 10 or newer.")
            }

            val requestedName = (args["filename"] as? String)?.let { sanitizeFilename(it) }
            val name = requestedName ?: sanitizeFilename(source.name)
            val mime = source.guessMimeType()
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = try {
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                null
            } ?: return mapOf("success" to false, "error" to "Could not create the Downloads entry.")

            return try {
                context.contentResolver.openOutputStream(uri).use { output ->
                    if (output == null) error("Could not open the Downloads entry")
                    source.inputStream().use { input -> input.copyTo(output) }
                }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
                mapOf(
                    "success" to true,
                    "uri" to uri.toString(),
                    "filename" to name,
                    "location" to "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR/$name",
                    "size_bytes" to source.length(),
                )
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                mapOf("success" to false, "error" to "Could not save to Downloads: ${e.message}")
            }
        }
    }
}

object ListDownloadsTool {

    const val ID = "list_downloads"

    val toolInfo = ToolInfo(
        id = ID,
        name = "List Downloads",
        description = "List files Pots saved to the device Downloads folder",
        nameRes = Res.string.tool_list_downloads_name,
        descriptionRes = Res.string.tool_list_downloads_description,
    )

    fun create(context: Context): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "List files in Download/Pots that were saved by Pots. Returns names, sizes " +
                "and content URIs.",
            parameters = mapOf(
                "query" to ParameterSchema("string", "Optional text matched against the filename", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return mapOf("success" to true, "count" to 0, "files" to emptyList<Any>())
            }
            val query = (args["query"] as? String)?.trim()
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_ADDED,
            )
            val selection = buildString {
                append("${MediaStore.Downloads.RELATIVE_PATH} LIKE ?")
                if (!query.isNullOrBlank()) append(" AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?")
            }
            val selectionArgs = buildList {
                add("%${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR%")
                if (!query.isNullOrBlank()) add("%$query%")
            }.toTypedArray()

            return try {
                val files = mutableListOf<Map<String, Any?>>()
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Downloads.DATE_ADDED} DESC",
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        files += mapOf(
                            "id" to id,
                            "name" to cursor.getString(nameIndex),
                            "uri" to ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id).toString(),
                            "mime_type" to cursor.getString(mimeIndex),
                            "size_bytes" to cursor.getLong(sizeIndex),
                            "date" to Instant.ofEpochSecond(cursor.getLong(dateIndex)).toString(),
                        )
                    }
                }
                mapOf("success" to true, "count" to files.size, "files" to files)
            } catch (e: Exception) {
                mapOf("success" to false, "error" to "Could not list Downloads: ${e.message}")
            }
        }
    }
}

private fun File.guessMimeType(): String = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"
