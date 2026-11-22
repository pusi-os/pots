package com.pusios.pots.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.Service
import com.pusios.pots.media.MediaKind
import com.pusios.pots.media.MediaModel
import com.pusios.pots.media.audioPriceLabel
import com.pusios.pots.media.getInstanceMediaAudioModelId
import com.pusios.pots.media.getInstanceMediaImageModelId
import com.pusios.pots.media.getInstanceMediaVideoModelId
import com.pusios.pots.media.mediaProviderFor
import com.pusios.pots.media.setInstanceMediaAudioModelId
import com.pusios.pots.media.setInstanceMediaImageModelId
import com.pusios.pots.media.setInstanceMediaVideoModelId
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.media_models_choose
import pots.composeapp.generated.resources.media_models_clear_search
import pots.composeapp.generated.resources.media_models_empty
import pots.composeapp.generated.resources.media_models_empty_hint
import pots.composeapp.generated.resources.media_models_hint
import pots.composeapp.generated.resources.media_models_load_failed
import pots.composeapp.generated.resources.media_models_loaded
import pots.composeapp.generated.resources.media_models_loading
import pots.composeapp.generated.resources.media_models_refresh
import pots.composeapp.generated.resources.media_models_retry
import pots.composeapp.generated.resources.media_models_search
import pots.composeapp.generated.resources.media_models_title
import pots.composeapp.generated.resources.media_section_audio
import pots.composeapp.generated.resources.media_section_images
import pots.composeapp.generated.resources.media_section_video
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Per-instance image/video model picker for the service its card belongs to.
 *
 * The provider comes from [mediaProviderFor], so a Polza card can only ever show
 * Polza's catalog, and a service without media endpoints renders nothing at all
 * (every card used to show PlusVibe's models — the bug this fixes).
 * The catalog is public, so the list loads without a key; generate validates the
 * key server-side.
 */
@Composable
fun MediaModelsSettings(service: Service, instanceId: String, apiKey: String) {
    val provider = remember(service) { mediaProviderFor(service) }
    if (provider == null) return

    val appSettings: AppSettings = koinInject()
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<MediaModel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadedAt by remember { mutableStateOf("") }
    var imageId by remember { mutableStateOf(appSettings.getInstanceMediaImageModelId(instanceId)) }
    var videoId by remember { mutableStateOf(appSettings.getInstanceMediaVideoModelId(instanceId)) }
    var audioId by remember { mutableStateOf(appSettings.getInstanceMediaAudioModelId(instanceId)) }

    suspend fun load() {
        loading = true
        error = null
        runCatching { provider.listModels(apiKey) }
            .onSuccess {
                models = it
                loadedAt = clockLabel()
            }
            .onFailure { error = it.message ?: it.toString() }
        loading = false
    }

    LaunchedEffect(instanceId, apiKey, service) {
        imageId = appSettings.getInstanceMediaImageModelId(instanceId)
        videoId = appSettings.getInstanceMediaVideoModelId(instanceId)
        audioId = appSettings.getInstanceMediaAudioModelId(instanceId)
        if (models.isEmpty()) load()
    }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Collections,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "${stringResource(Res.string.media_models_title)} · ${service.displayName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (models.isNotEmpty() && !loading) {
                TextButton(onClick = { scope.launch { load() } }) {
                    Text(stringResource(Res.string.media_models_refresh), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            stringResource(Res.string.media_models_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        when {
            loading -> Text(
                stringResource(Res.string.media_models_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )

            error != null -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.media_models_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch { load() } }) {
                    Text(stringResource(Res.string.media_models_retry), style = MaterialTheme.typography.labelSmall)
                }
            }

            models.isNotEmpty() -> Text(
                stringResource(Res.string.media_models_loaded, models.size, loadedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        ModelPicker(
            title = stringResource(Res.string.media_section_images),
            icon = Icons.Outlined.Image,
            models = models.filter { it.kind == MediaKind.Image },
            selectedId = imageId,
            onSelect = {
                imageId = it
                appSettings.setInstanceMediaImageModelId(instanceId, it)
            },
        )
        ModelPicker(
            title = stringResource(Res.string.media_section_video),
            icon = Icons.Outlined.Movie,
            models = models.filter { it.kind == MediaKind.Video },
            selectedId = videoId,
            onSelect = {
                videoId = it
                appSettings.setInstanceMediaVideoModelId(instanceId, it)
            },
        )
        ModelPicker(
            title = stringResource(Res.string.media_section_audio),
            icon = Icons.Outlined.GraphicEq,
            models = models.filter { it.kind == MediaKind.Audio },
            selectedId = audioId,
            onSelect = {
                audioId = it
                appSettings.setInstanceMediaAudioModelId(instanceId, it)
            },
        )
    }
}

/**
 * One media section: the header from the design spec (icon + title + count) and
 * the dropdown below it. Prices keep the notation the catalogs publish
 * («от 12 ₽ за файл»); media providers do not report per-token prices, so the
 * field shows the same text as the row instead of two empty token columns.
 */
@Composable
private fun ModelPicker(
    title: String,
    icon: ImageVector,
    models: List<MediaModel>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    if (models.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${models.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val selected = models.firstOrNull { it.id == selectedId }
    SettingsDropdown(
        placeholder = stringResource(Res.string.media_models_choose),
        selectedLabel = selected?.id,
        selectedTrailing = selected?.let { audioPriceLabel(it) }.orEmpty(),
        searchPlaceholder = stringResource(Res.string.media_models_search),
        emptyTitle = stringResource(Res.string.media_models_empty),
        emptyHint = { query -> stringResource(Res.string.media_models_empty_hint, query) },
        clearSearchLabel = stringResource(Res.string.media_models_clear_search),
        items = models,
        itemKey = { it.id },
        itemLabel = { it.id },
        itemTrailing = { audioPriceLabel(it) },
        selectedKey = selectedId,
        onSelect = { onSelect(it.id) },
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** `12:34` — when the catalog was fetched, so the list's freshness is on screen. */
@OptIn(ExperimentalTime::class)
private fun clockLabel(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
}
