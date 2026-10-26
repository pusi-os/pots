package com.pusios.pots.ui.chat.composables

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import java.io.File

@Composable
internal actual fun rememberAttachmentPickerLauncher(
    extensions: List<String>,
    chooserTitle: String,
    includeCamera: Boolean,
    onFile: (PlatformFile?) -> Unit,
): AttachmentPickerLauncher {
    val context = LocalContext.current
    val currentOnFile by rememberUpdatedState(onFile)
    val pendingUri = remember { mutableStateOf<Uri?>(null) }
    val pendingFile = remember { mutableStateOf<File?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val returned = result.data?.data
        val cameraUri = pendingUri.value
        val cameraFile = pendingFile.value
        val ok = result.resultCode == Activity.RESULT_OK

        val uri = when {
            ok && returned != null -> returned
            ok && cameraUri != null -> cameraUri
            else -> null
        }

        when {
            uri == null -> {
                cameraFile?.delete()
                currentOnFile(null)
            }

            uri == cameraUri && (cameraFile == null || cameraFile.length() == 0L) -> {
                cameraFile?.delete()
                currentOnFile(null)
            }

            else -> {
                if (uri != cameraUri) cameraFile?.delete()
                currentOnFile(PlatformFile(AndroidFile.UriWrapper(uri)))
            }
        }

        pendingUri.value = null
        pendingFile.value = null
    }

    return remember(context, launcher, extensions, chooserTitle, includeCamera) {
        AttachmentPickerLauncher {
            val fileIntent = documentPickerIntent(extensions)
            val camera = if (includeCamera) createCameraTarget(context) else null
            pendingUri.value = camera?.uri
            pendingFile.value = camera?.file

            val chooser = Intent.createChooser(fileIntent, chooserTitle).apply {
                camera?.let { target ->
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    target.cameraPackage?.let { packageName ->
                        context.grantUriPermission(
                            packageName,
                            target.uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<Parcelable>(target.intent))
                }
            }
            launcher.launch(chooser)
        }
    }
}

private fun documentPickerIntent(extensions: List<String>): Intent {
    val mimeTypes = extensions
        .mapNotNull { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
        .distinct()
    return Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        if (mimeTypes.isNotEmpty()) {
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private data class CameraTarget(
    val uri: Uri,
    val file: File,
    val intent: Intent,
    val cameraPackage: String?,
)

private fun createCameraTarget(context: Context): CameraTarget? {
    val directory = File(context.cacheDir, "chat-camera").apply { mkdirs() }
    val file = File(directory, "photo-${System.currentTimeMillis()}.jpg")
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return null

    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
        putExtra(MediaStore.EXTRA_OUTPUT, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    val cameraPackage = intent.resolveActivity(context.packageManager)?.packageName
    return CameraTarget(uri, file, intent, cameraPackage)
}
