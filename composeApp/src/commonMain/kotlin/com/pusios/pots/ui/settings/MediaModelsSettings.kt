package com.pusios.pots.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.AppSettings
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.PlusVibeMediaProvider
import com.pusios.pots.media.getInstanceMediaImageModelId
import com.pusios.pots.media.getInstanceMediaVideoModelId
import com.pusios.pots.media.setInstanceMediaImageModelId
import com.pusios.pots.media.setInstanceMediaVideoModelId
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Per-instance image/video model picker. The catalog is public, so the list
 * loads without a key; generate validates the key server-side.
 */
@Composable
fun MediaModelsSettings(instanceId: String, apiKey: String) {
    val appSettings: AppSettings = koinInject()
    val scope = rememberCoroutineScope()
    val provider = remember { PlusVibeMediaProvider() }
    var models by remember { mutableStateOf<List<MediaModel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var imageId by remember { mutableStateOf(appSettings.getInstanceMediaImageModelId(instanceId)) }
    var videoId by remember { mutableStateOf(appSettings.getInstanceMediaVideoModelId(instanceId)) }

    suspend fun load() {
        loading = true
        error = null
        runCatching { provider.listModels(apiKey) }
            .onSuccess { models = it }
            .onFailure { error = it.message }
        loading = false
    }

    LaunchedEffect(instanceId, apiKey) {
        imageId = appSettings.getInstanceMediaImageModelId(instanceId)
        videoId = appSettings.getInstanceMediaVideoModelId(instanceId)
    }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("Медиа-модели (PlusVibe)", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Ключ используется тот же, что и для чата. Список загружается из публичного каталога.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { scope.launch { load() } }, modifier = Modifier.padding(top = 8.dp)) {
            Text(if (loading) "Загрузка…" else "Загрузить список и цены")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        ModelPicker(
            title = "Изображения",
            models = models.filter { it.kind == MediaKind.Image },
            selectedId = imageId,
            onSelect = {
                imageId = it
                appSettings.setInstanceMediaImageModelId(instanceId, it)
            },
        )
        ModelPicker(
            title = "Видео",
            models = models.filter { it.kind == MediaKind.Video },
            selectedId = videoId,
            onSelect = {
                videoId = it
                appSettings.setInstanceMediaVideoModelId(instanceId, it)
            },
        )
    }
}

@Composable
private fun ModelPicker(title: String, models: List<MediaModel>, selectedId: String, onSelect: (String) -> Unit) {
    if (models.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    models.take(30).forEach { model ->
        val selected = model.id == selectedId
        Surface(
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                .clickable { onSelect(model.id) },
        ) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(model.id, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                val price = model.fromRub?.let { "от $it ₽" } ?: ""
                val unit = model.unit.orEmpty()
                Text(
                    (price + if (price.isNotEmpty() && unit.isNotEmpty()) " · $unit" else unit),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
