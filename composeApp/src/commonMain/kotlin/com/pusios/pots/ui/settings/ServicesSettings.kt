@file:OptIn(ExperimentalMaterial3Api::class)

package com.pusios.pots.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pusios.pots.data.AppSettings
import com.pusios.pots.data.ProviderBalance
import com.pusios.pots.data.Service
import com.pusios.pots.data.ServiceInstance
import com.pusios.pots.formatFileSize
import com.pusios.pots.inference.DevicePerformance
import com.pusios.pots.inference.DownloadError
import com.pusios.pots.inference.LocalModel
import com.pusios.pots.inference.ModelImportError
import com.pusios.pots.inference.calculateDevicePerformance
import com.pusios.pots.inference.estimateGpuMemoryMb
import com.pusios.pots.media.activeMediaService
import com.pusios.pots.media.mediaProviderFor
import com.pusios.pots.media.mediaServiceChoices
import com.pusios.pots.media.setMediaServiceInstanceId
import com.pusios.pots.network.dtos.SponsorsResponseDto
import com.pusios.pots.ui.PotsClearableTextField
import com.pusios.pots.ui.components.PotsSlider
import com.pusios.pots.ui.components.VerticalScrollbarForScroll
import com.pusios.pots.ui.handCursor
import com.pusios.pots.ui.icons.ClubCloud
import com.pusios.pots.ui.icons.ClubKey
import com.pusios.pots.ui.icons.DragIndicator
import com.pusios.pots.ui.icons.PotsDevice
import com.pusios.pots.ui.icons.PotsGame
import com.pusios.pots.ui.icons.Replay
import com.pusios.pots.ui.potsAdaptiveCardBorder
import com.pusios.pots.ui.potsAdaptiveCardColors
import com.pusios.pots.ui.potsAdaptiveCardSurface
import com.pusios.pots.update.UpdateError
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.koinInject
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.ic_arrow_drop_down
import pots.composeapp.generated.resources.litert_cancel
import pots.composeapp.generated.resources.litert_context_size
import pots.composeapp.generated.resources.litert_download
import pots.composeapp.generated.resources.litert_error_checksum_mismatch
import pots.composeapp.generated.resources.litert_error_download_incomplete
import pots.composeapp.generated.resources.litert_error_import_failed
import pots.composeapp.generated.resources.litert_error_import_invalid
import pots.composeapp.generated.resources.litert_error_import_too_small
import pots.composeapp.generated.resources.litert_error_network
import pots.composeapp.generated.resources.litert_error_not_enough_disk_space
import pots.composeapp.generated.resources.litert_free_space
import pots.composeapp.generated.resources.litert_import
import pots.composeapp.generated.resources.litert_import_description
import pots.composeapp.generated.resources.litert_imported
import pots.composeapp.generated.resources.litert_importing
import pots.composeapp.generated.resources.litert_on_device_description
import pots.composeapp.generated.resources.litert_performance_good
import pots.composeapp.generated.resources.litert_performance_ok
import pots.composeapp.generated.resources.litert_performance_poor
import pots.composeapp.generated.resources.litert_recommended
import pots.composeapp.generated.resources.litert_tool_support
import pots.composeapp.generated.resources.media_models_clear_search
import pots.composeapp.generated.resources.media_models_empty
import pots.composeapp.generated.resources.media_models_search
import pots.composeapp.generated.resources.media_service_catalog_failed
import pots.composeapp.generated.resources.media_service_empty_hint
import pots.composeapp.generated.resources.media_service_hint
import pots.composeapp.generated.resources.media_service_models
import pots.composeapp.generated.resources.media_service_title
import pots.composeapp.generated.resources.settings_add_service
import pots.composeapp.generated.resources.settings_api_key_label
import pots.composeapp.generated.resources.settings_api_key_optional_label
import pots.composeapp.generated.resources.settings_base_url_label
import pots.composeapp.generated.resources.settings_custom_model_hint
import pots.composeapp.generated.resources.settings_custom_model_label
import pots.composeapp.generated.resources.settings_free_fallback
import pots.composeapp.generated.resources.settings_maintenance_description
import pots.composeapp.generated.resources.settings_maintenance_title
import pots.composeapp.generated.resources.settings_model_label
import pots.composeapp.generated.resources.settings_open_app_settings
import pots.composeapp.generated.resources.settings_openai_compatible_or_other_service
import pots.composeapp.generated.resources.settings_openai_compatible_providers
import pots.composeapp.generated.resources.settings_openai_compatible_setup_ollama
import pots.composeapp.generated.resources.settings_potsgame_description
import pots.composeapp.generated.resources.settings_potsgame_fallback_note
import pots.composeapp.generated.resources.settings_potsgame_title
import pots.composeapp.generated.resources.settings_remove_service
import pots.composeapp.generated.resources.settings_reorder_content_description
import pots.composeapp.generated.resources.settings_restart_onboarding
import pots.composeapp.generated.resources.settings_restart_onboarding_hint
import pots.composeapp.generated.resources.settings_sign_in_copy_api_key_from
import pots.composeapp.generated.resources.settings_sponsors
import pots.composeapp.generated.resources.settings_status_checking
import pots.composeapp.generated.resources.settings_status_connected
import pots.composeapp.generated.resources.settings_status_error
import pots.composeapp.generated.resources.settings_status_error_connection_failed
import pots.composeapp.generated.resources.settings_status_error_invalid_key
import pots.composeapp.generated.resources.settings_status_error_local_network
import pots.composeapp.generated.resources.settings_status_error_quota_exhausted
import pots.composeapp.generated.resources.settings_status_error_rate_limited
import pots.composeapp.generated.resources.settings_telegram_nerosekta
import pots.composeapp.generated.resources.settings_update_app
import pots.composeapp.generated.resources.settings_update_cancel
import pots.composeapp.generated.resources.settings_update_checking
import pots.composeapp.generated.resources.settings_update_done
import pots.composeapp.generated.resources.settings_update_done_manual
import pots.composeapp.generated.resources.settings_update_done_restart
import pots.composeapp.generated.resources.settings_update_downloading
import pots.composeapp.generated.resources.settings_update_error_corrupt
import pots.composeapp.generated.resources.settings_update_error_incomplete
import pots.composeapp.generated.resources.settings_update_error_invalid_response
import pots.composeapp.generated.resources.settings_update_error_network
import pots.composeapp.generated.resources.settings_update_error_no_asset
import pots.composeapp.generated.resources.settings_update_error_not_enough_space
import pots.composeapp.generated.resources.settings_update_error_not_found
import pots.composeapp.generated.resources.settings_update_error_rate_limited
import pots.composeapp.generated.resources.settings_update_error_server
import pots.composeapp.generated.resources.settings_update_error_unsupported
import pots.composeapp.generated.resources.settings_update_installing
import pots.composeapp.generated.resources.settings_update_retry
import pots.composeapp.generated.resources.settings_update_unpacking
import pots.composeapp.generated.resources.settings_update_unsupported
import pots.composeapp.generated.resources.settings_update_up_to_date
import sh.calvin.reorderable.ReorderableColumn
import kotlin.math.roundToInt

@Composable
internal fun PotsGameSection(
    currentSponsors: ImmutableList<SponsorsResponseDto.Sponsor> = persistentListOf(),
    pastSponsors: ImmutableList<SponsorsResponseDto.Sponsor> = persistentListOf(),
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = potsAdaptiveCardColors(),
        border = potsAdaptiveCardBorder(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.PotsGame,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.settings_potsgame_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            PotsGameInfographic()

            Text(
                text = stringResource(Res.string.settings_potsgame_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(6.dp))

            // The fallback flag is retired with the free tier: visible but disabled,
            // without a clickable row, so the option doesn't disappear silently.
            val disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.settings_free_fallback),
                    style = MaterialTheme.typography.bodyMedium,
                    color = disabledContentColor,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = false,
                    onCheckedChange = null,
                    enabled = false,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(Res.string.settings_potsgame_fallback_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            val uriHandler = LocalUriHandler.current
            Button(
                onClick = {
                    uriHandler.openUri("https://t.me/nerosekta")
                },
                Modifier
                    .align(CenterHorizontally)
                    .handCursor(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.settings_telegram_nerosekta))
            }

            val allSponsors = remember(currentSponsors, pastSponsors) {
                val activeUsernames = currentSponsors.map { it.username }.toSet()
                (currentSponsors + pastSponsors.filter { it.username !in activeUsernames })
                    .toImmutableList()
            }

            if (allSponsors.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(thickness = 0.5.dp)
                Spacer(Modifier.height(16.dp))
                SponsorList(
                    title = stringResource(Res.string.settings_sponsors),
                    sponsors = allSponsors,
                )
            }
        }
    }
}

/**
 * Onboarding replay plus the self-update, right under the PotsGame club card.
 *
 * Both buttons are deliberate, one-shot actions: nothing is downloaded until «Обновить
 * приложение» is pressed, and the onboarding restart only rewrites the persisted wizard flags.
 */
@Composable
internal fun AppMaintenanceSection(
    update: AppUpdateUiState,
    onRestartOnboarding: () -> Unit,
    onUpdateApp: () -> Unit,
    onCancelUpdate: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = potsAdaptiveCardColors(),
        border = potsAdaptiveCardBorder(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.PotsDevice,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.settings_maintenance_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(Res.string.settings_maintenance_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onRestartOnboarding,
                modifier = Modifier
                    .fillMaxWidth()
                    .handCursor(),
            ) {
                Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.settings_restart_onboarding))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.settings_restart_onboarding_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onUpdateApp,
                enabled = update.isSupported && !update.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .handCursor(),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.settings_update_app))
            }

            UpdateStatusContent(
                update = update,
                onUpdateApp = onUpdateApp,
                onCancelUpdate = onCancelUpdate,
            )
        }
    }
}

@Composable
private fun UpdateStatusContent(
    update: AppUpdateUiState,
    onUpdateApp: () -> Unit,
    onCancelUpdate: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (update.phase) {
        UpdatePhase.Idle -> if (!update.isSupported) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.settings_update_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }

        UpdatePhase.Checking -> {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.settings_update_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }

        UpdatePhase.Downloading -> {
            Spacer(Modifier.height(8.dp))
            val progress = update.progress
            LinearProgressIndicator(
                progress = { progress?.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    Res.string.settings_update_downloading,
                    "${((progress?.fraction ?: 0f) * 100).roundToInt()} %",
                    formatFileSize(progress?.bytesPerSecond ?: 0L),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            TextButton(onClick = onCancelUpdate, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.settings_update_cancel))
            }
        }

        UpdatePhase.Unpacking -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.settings_update_unpacking),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }

        UpdatePhase.Installing -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.settings_update_installing),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }

        UpdatePhase.UpToDate -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.settings_update_up_to_date, update.versionName.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }

        UpdatePhase.Done -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.settings_update_done, update.fileName.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    if (update.installerOpened) {
                        Res.string.settings_update_done_restart
                    } else {
                        Res.string.settings_update_done_manual
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }

        UpdatePhase.Failed -> {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = updateErrorText(update.error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onUpdateApp, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.settings_update_retry))
            }
        }
    }
}

/** Every failure ends with what the user can do about it, never with a bare exception name. */
@Composable
private fun updateErrorText(error: UpdateError?): String = when (error) {
    UpdateError.NetworkError -> stringResource(Res.string.settings_update_error_network)
    UpdateError.RateLimited -> stringResource(Res.string.settings_update_error_rate_limited)
    UpdateError.NotFound -> stringResource(Res.string.settings_update_error_not_found)
    UpdateError.ServerError -> stringResource(Res.string.settings_update_error_server)
    UpdateError.InvalidResponse -> stringResource(Res.string.settings_update_error_invalid_response)
    UpdateError.NotEnoughSpace -> stringResource(Res.string.settings_update_error_not_enough_space)
    UpdateError.IncompleteDownload -> stringResource(Res.string.settings_update_error_incomplete)
    UpdateError.CorruptArchive -> stringResource(Res.string.settings_update_error_corrupt)
    UpdateError.NoAssetForPlatform -> stringResource(Res.string.settings_update_error_no_asset)
    UpdateError.UnsupportedPlatform, null -> stringResource(Res.string.settings_update_error_unsupported)
}

/**
 * «устройство → ключ → облако»: the device (neutral) connects through the club key
 * (accent) to the pots.guru cloud (primary). A Compose composition instead of a
 * monolithic asset, so it inherits the theme colors and stays vector-crisp.
 */
@Composable
private fun PotsGameInfographic() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PotsDevice,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.weight(1f).height(28.dp)) {
            val dashColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            Canvas(Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center)) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = dashColor,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            }
            // The round backing breaks the dashes under the glyph so the key stays readable.
            Surface(
                modifier = Modifier.align(Alignment.Center).size(24.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.ClubKey,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Icon(
            imageVector = Icons.Filled.ClubCloud,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SponsorList(
    title: String,
    sponsors: ImmutableList<SponsorsResponseDto.Sponsor>,
) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The tile width is sized for the username at the default font scale; without
        // scaling it, names are cut to three characters at the largest one.
        val tileWidth = 72.dp * LocalDensity.current.fontScale
        sponsors.forEach { sponsor ->
            Column(
                horizontalAlignment = CenterHorizontally,
                modifier = Modifier
                    .width(tileWidth)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { uriHandler.openUri("https://github.com/${sponsor.username}") }
                    .handCursor()
                    .padding(4.dp),
            ) {
                coil3.compose.AsyncImage(
                    model = sponsor.avatar,
                    contentDescription = sponsor.username,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = sponsor.username,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun ServicesContent(
    uiState: SettingsUiState,
    actions: SettingsActions,
    onRestartOnboarding: () -> Unit = {},
) {
    var showAddServiceSheet by remember { mutableStateOf(false) }

    // Configured services list
    val entries = uiState.configuredServices
    ReorderableColumn(
        list = entries,
        onSettle = { fromIndex, toIndex ->
            val ids = entries.map { it.instanceId }.toMutableList()
            ids.add(toIndex, ids.removeAt(fromIndex))
            actions.onReorderServices(ids)
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { _, entry, isDragging ->
        key(entry.instanceId) {
            ReorderableItem {
                ConfiguredServiceCardContent(
                    entry = entry,
                    isExpanded = uiState.expandedServiceId == entry.instanceId,
                    onExpand = { actions.onExpandService(if (uiState.expandedServiceId == entry.instanceId) null else entry.instanceId) },
                    onChangeApiKey = { apiKey -> actions.onChangeApiKey(entry.instanceId, apiKey) },
                    onChangeBaseUrl = { baseUrl -> actions.onChangeBaseUrl(entry.instanceId, baseUrl) },
                    onSelectModel = { modelId -> actions.onSelectModel(entry.instanceId, modelId) },
                    onToggleUseCustomModel = { use -> actions.onToggleUseCustomModel(entry.instanceId, use) },
                    onChangeCustomModelId = { id -> actions.onChangeCustomModelId(entry.instanceId, id) },
                    onRemove = { actions.onRemoveService(entry.instanceId) },
                    isDragging = isDragging,
                    dragHandleModifier = if (entries.size >= 2) Modifier.draggableHandle() else null,
                    localAvailableModels = uiState.localAvailableModels,
                    localImportedModels = uiState.localImportedModels,
                    totalDeviceMemoryBytes = uiState.totalDeviceMemoryBytes,
                    localFreeSpaceBytes = uiState.localFreeSpaceBytes,
                    localDownloadingModelId = uiState.localDownloadingModelId,
                    localDownloadProgress = uiState.localDownloadProgress,
                    localDownloadError = uiState.localDownloadError,
                    localImportingFileName = uiState.localImportingFileName,
                    localImportProgress = uiState.localImportProgress,
                    localImportError = uiState.localImportError,
                    onDownloadLocalModel = actions.onDownloadLocalModel,
                    onCancelLocalModelDownload = actions.onCancelLocalModelDownload,
                    onImportLocalModel = actions.onImportLocalModel,
                    onCancelLocalModelImport = actions.onCancelLocalModelImport,
                    onDeleteLocalModel = actions.onDeleteLocalModel,
                    onChangeModelContextTokens = actions.onChangeModelContextTokens,
                    modelContextTokens = uiState.modelContextTokens,
                    onOpenAppPermissionSettings = actions.onOpenAppPermissionSettings,
                    onRecheckLocalNetworkPermission = { actions.onRecheckLocalNetworkPermission(entry.instanceId) },
                )
            }
        }
    }

    if (uiState.availableServicesToAdd.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { showAddServiceSheet = true }, modifier = Modifier.handCursor()) {
            Text(stringResource(Res.string.settings_add_service))
        }
    }

    MediaServiceSection(entries)

    // PotsGame club card (always at bottom)
    Spacer(Modifier.height(16.dp))
    PotsGameSection(
        currentSponsors = uiState.currentSponsors,
        pastSponsors = uiState.pastSponsors,
    )

    // Onboarding replay and the GitHub self-update, right after the club card.
    Spacer(Modifier.height(16.dp))
    AppMaintenanceSection(
        update = uiState.update,
        onRestartOnboarding = onRestartOnboarding,
        onUpdateApp = actions.onUpdateApp,
        onCancelUpdate = actions.onCancelUpdate,
    )

    // Add service bottom sheet
    if (showAddServiceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddServiceSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            val addServiceScrollState = rememberScrollState()
            Box {
                Column(modifier = Modifier.verticalScroll(addServiceScrollState).padding(16.dp)) {
                    val services = uiState.availableServicesToAdd
                    services.forEachIndexed { index, service ->
                        val isFirst = index == 0
                        val isLast = index == services.lastIndex
                        val itemShape = RoundedCornerShape(
                            topStart = if (isFirst) 12.dp else 0.dp,
                            topEnd = if (isFirst) 12.dp else 0.dp,
                            bottomStart = if (isLast) 12.dp else 0.dp,
                            bottomEnd = if (isLast) 12.dp else 0.dp,
                        )
                        val isSpecial = service.isOnDevice || service is Service.OpenAICompatible
                        Surface(
                            onClick = {
                                actions.onAddService(service)
                                showAddServiceSheet = false
                            },
                            modifier = Modifier.fillMaxWidth().handCursor(),
                            shape = itemShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .then(
                                            if (isSpecial) {
                                                Modifier.background(
                                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    shape = RoundedCornerShape(8.dp),
                                                )
                                            } else {
                                                Modifier
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = vectorResource(service.icon),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onBackground,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = service.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                VerticalScrollbarForScroll(
                    scrollState = addServiceScrollState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * «Сервис для медиатеки»: which configured service the media studio and the
 * agent's media tools use. The candidate list comes from the factory
 * ([mediaServiceChoices]) — a service without media endpoints never shows up,
 * and with a single candidate the section hides itself (nothing to choose).
 */
@Composable
private fun MediaServiceSection(entries: List<ConfiguredServiceEntry>) {
    // No Koin container under Paparazzi/LocalInspectionMode; the section needs
    // AppSettings, so screenshots render the list without it (as AgentSettings does).
    if (LocalInspectionMode.current) return
    val appSettings: AppSettings = koinInject()
    val instances = remember(entries) { entries.map { ServiceInstance(it.instanceId, it.service.id) } }
    val choices = remember(entries) { mediaServiceChoices(appSettings, instances) }
    if (choices.size < 2) return

    // The saved instance while it is still valid, else what the resolver picks.
    val active = remember(entries) { activeMediaService(appSettings, instances) }
    var chosenId by remember(entries) { mutableStateOf(active?.instanceId.orEmpty()) }
    val selected = choices.firstOrNull { it.instanceId == chosenId } ?: active?.let { a -> choices.firstOrNull { it.instanceId == a.instanceId } } ?: choices.first()

    // Model counts per candidate, loaded once when the section appears.
    var counts by remember(entries) { mutableStateOf<Map<String, Int?>>(emptyMap()) }
    LaunchedEffect(choices.map { it.instanceId }) {
        choices.forEach { choice ->
            if (counts.containsKey(choice.instanceId)) return@forEach
            val size = runCatching { mediaProviderFor(choice.service)?.listModels(choice.apiKey)?.size }.getOrNull()
            counts = counts + (choice.instanceId to size)
        }
    }
    val failedLabel = stringResource(Res.string.media_service_catalog_failed)
    // Labels are resolved here, not inside a lambda: stringResource is composable.
    val trailing = mutableMapOf<String, String>()
    for (choice in choices) {
        val count = counts[choice.instanceId]
        trailing[choice.instanceId] = when {
            !counts.containsKey(choice.instanceId) -> ""
            count == null -> failedLabel
            else -> stringResource(Res.string.media_service_models, count)
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                text = stringResource(Res.string.media_service_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(Res.string.media_service_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        SettingsDropdown(
            placeholder = stringResource(Res.string.media_service_title),
            selectedLabel = selected.service.displayName,
            selectedTrailing = trailing[selected.instanceId].orEmpty(),
            searchPlaceholder = stringResource(Res.string.media_models_search),
            emptyTitle = stringResource(Res.string.media_models_empty),
            emptyHint = { stringResource(Res.string.media_service_empty_hint) },
            clearSearchLabel = stringResource(Res.string.media_models_clear_search),
            items = choices,
            itemKey = { it.instanceId },
            itemLabel = { it.service.displayName },
            itemTrailing = { trailing[it.instanceId].orEmpty() },
            selectedKey = selected.instanceId,
            onSelect = { choice ->
                appSettings.setMediaServiceInstanceId(choice.instanceId)
                chosenId = choice.instanceId
            },
        )
    }
}

private fun formatProviderBalance(balance: ProviderBalance): String {
    val symbol = when (balance.currency.uppercase()) {
        "RUB" -> "₽"
        "USD" -> "$"
        "EUR" -> "€"
        else -> balance.currency
    }
    val cents = kotlin.math.round(balance.amount * 100).toLong()
    val whole = cents / 100
    val fraction = kotlin.math.abs(cents % 100).toString().padStart(2, '0')
    return "$whole.$fraction $symbol"
}

@Composable
private fun ConfiguredServiceCardContent(
    entry: ConfiguredServiceEntry,
    isExpanded: Boolean,
    onExpand: () -> Unit,
    onChangeApiKey: (String) -> Unit,
    onChangeBaseUrl: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onToggleUseCustomModel: (Boolean) -> Unit = {},
    onChangeCustomModelId: (String) -> Unit = {},
    onRemove: () -> Unit,
    isDragging: Boolean = false,
    dragHandleModifier: Modifier? = null,
    localAvailableModels: ImmutableList<LocalModel> = persistentListOf(),
    localImportedModels: ImmutableList<LocalModel> = persistentListOf(),
    totalDeviceMemoryBytes: Long = Long.MAX_VALUE,
    localFreeSpaceBytes: Long = 0L,
    localDownloadingModelId: String? = null,
    localDownloadProgress: Float? = null,
    localDownloadError: DownloadError? = null,
    localImportingFileName: String? = null,
    localImportProgress: Float? = null,
    localImportError: ModelImportError? = null,
    onDownloadLocalModel: (LocalModel) -> Unit = {},
    onCancelLocalModelDownload: () -> Unit = {},
    onImportLocalModel: (PlatformFile) -> Unit = {},
    onCancelLocalModelImport: () -> Unit = {},
    onDeleteLocalModel: (String) -> Unit = {},
    onChangeModelContextTokens: (String, Int) -> Unit = { _, _ -> },
    modelContextTokens: ImmutableMap<String, Int> = persistentMapOf(),
    onOpenAppPermissionSettings: () -> Unit = {},
    onRecheckLocalNetworkPermission: () -> Unit = {},
) {
    // Clear a stale denied status when the user returns from granting the permission in
    // system settings; the recheck never re-prompts, so this is a no-op while still denied.
    if (entry.connectionStatus == ConnectionStatus.ErrorLocalNetworkDenied) {
        LifecycleResumeEffect(entry.instanceId) {
            onRecheckLocalNetworkPermission()
            onPauseOrDispose { }
        }
    }
    Column(
        modifier = Modifier
            .potsAdaptiveCardSurface()
            .fillMaxWidth()
            .clickable { onExpand() }
            .handCursor(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Drag handle
                if (dragHandleModifier != null) {
                    Icon(
                        imageVector = Icons.Rounded.DragIndicator,
                        contentDescription = stringResource(Res.string.settings_reorder_content_description),
                        modifier = dragHandleModifier.handCursor(),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                }

                // Connection status dot
                val dotColor = when (entry.connectionStatus) {
                    ConnectionStatus.Connected -> StatusColorConnected
                    ConnectionStatus.Checking -> StatusColorChecking
                    ConnectionStatus.Unknown -> StatusColorUnknown
                    else -> StatusColorError
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )

                Spacer(Modifier.width(12.dp))

                // Service name and model
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.service.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    val displayModelId = when {
                        entry.useCustomModel && entry.customModelId.isNotBlank() -> entry.customModelId
                        entry.selectedModel != null -> entry.selectedModel.id
                        else -> null
                    }
                    if (displayModelId != null) {
                        Text(
                            text = displayModelId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                entry.balance?.let { balance ->
                    Text(
                        text = formatProviderBalance(balance),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                }

                // Expand/collapse chevron
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Expanded content
        if (isExpanded) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                if (entry.service.isOnDevice) {
                    val localEngineAvailable = if (androidx.compose.ui.platform.LocalInspectionMode.current) {
                        true
                    } else {
                        koinInject<com.pusios.pots.data.DataRepository>().isLocalInferenceAvailable()
                    }
                    if (!localEngineAvailable) {
                        Text(
                            text = "Инструмент не поддерживается на этом компьютере.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LiteRTSettings(
                            selectedModel = entry.selectedModel,
                            downloadedModels = entry.models,
                            availableModels = localAvailableModels,
                            importedModels = localImportedModels,
                            totalDeviceMemoryBytes = totalDeviceMemoryBytes,
                            freeSpaceBytes = localFreeSpaceBytes,
                            downloadingModelId = localDownloadingModelId,
                            downloadProgress = localDownloadProgress,
                            downloadError = localDownloadError,
                            importingFileName = localImportingFileName,
                            importProgress = localImportProgress,
                            importError = localImportError,
                            onSelectModel = onSelectModel,
                            onDownloadModel = onDownloadLocalModel,
                            onCancelDownload = onCancelLocalModelDownload,
                            onImportModel = onImportLocalModel,
                            onCancelImport = onCancelLocalModelImport,
                            onDeleteModel = onDeleteLocalModel,
                            onChangeModelContextTokens = onChangeModelContextTokens,
                            modelContextTokens = modelContextTokens,
                        )
                    }
                } else if (entry.service is Service.OpenAICompatible) {
                    OpenAICompatibleSettings(
                        baseUrl = entry.baseUrl,
                        onChangeBaseUrl = onChangeBaseUrl,
                        apiKey = entry.apiKey,
                        onChangeApiKey = onChangeApiKey,
                        selectedModel = entry.selectedModel,
                        models = entry.models,
                        onSelectModel = onSelectModel,
                        useCustomModel = entry.useCustomModel,
                        customModelId = entry.customModelId,
                        onToggleUseCustomModel = onToggleUseCustomModel,
                        onChangeCustomModelId = onChangeCustomModelId,
                        connectionStatus = entry.connectionStatus,
                        onOpenAppPermissionSettings = onOpenAppPermissionSettings,
                    )
                } else {
                    ServiceSettings(
                        service = entry.service,
                        apiKey = entry.apiKey,
                        onChangeApiKey = onChangeApiKey,
                        apiKeyUrl = entry.service.apiKeyUrl ?: "",
                        apiKeyUrlDisplay = entry.service.apiKeyUrlDisplay ?: "",
                        selectedModel = entry.selectedModel,
                        models = entry.models,
                        onSelectModel = onSelectModel,
                        connectionStatus = entry.connectionStatus,
                        onOpenAppPermissionSettings = onOpenAppPermissionSettings,
                    )
                }

                if (!entry.service.isOnDevice) {
                    MediaModelsSettings(service = entry.service, instanceId = entry.instanceId, apiKey = entry.apiKey)
                }

                Spacer(Modifier.height(12.dp))

                // Remove action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onRemove,
                        modifier = Modifier.handCursor(),
                    ) {
                        Text(
                            text = stringResource(Res.string.settings_remove_service),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceSettings(
    service: Service? = null,
    apiKey: String,
    onChangeApiKey: (String) -> Unit,
    apiKeyUrl: String,
    apiKeyUrlDisplay: String,
    selectedModel: SettingsModel?,
    models: ImmutableList<SettingsModel>,
    onSelectModel: (String) -> Unit,
    connectionStatus: ConnectionStatus,
    testTag: String? = null,
    onOpenAppPermissionSettings: () -> Unit = {},
) {
    ApiKeyField(
        apiKey = apiKey,
        onChangeApiKey = onChangeApiKey,
        labelText = stringResource(Res.string.settings_api_key_label),
        testTag = testTag,
    )

    Spacer(Modifier.height(8.dp))

    ConnectionStatusIndicator(connectionStatus, onOpenAppPermissionSettings)

    Spacer(Modifier.height(8.dp))

    val isReferralProvider = service in listOf(Service.Polza, Service.Gptunnel, Service.AiTunnel, Service.PlusVibe, Service.PlusVibeReserve, Service.LlmRouter)
    if (isReferralProvider) {
        val uriHandler = LocalUriHandler.current
        val instruction = when (service) {
            Service.Polza -> "Polza.ai → Вход/Регистрация → API-Ключи → Создать и скопировать ключ → Вставить здесь"
            Service.Gptunnel -> "GPTunnel → Вход/Регистрация → Профиль → API-Ключи → Создать и скопировать ключ → Вставить здесь"
            Service.AiTunnel -> "AITUNNEL → Вход/Регистрация → Ключи API → Создать и скопировать ключ → Вставить здесь"
            Service.PlusVibe, Service.PlusVibeReserve -> "PlusVibeAPI → Личный кабинет → API-Ключи → Создать и скопировать ключ → Вставить здесь"
            Service.LlmRouter -> "LLM Router → Регистрация → API-ключ → Создать и скопировать ключ → Вставить здесь"
            else -> ""
        }
        Text(
            text = instruction,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { uriHandler.openUri(apiKeyUrl) },
            modifier = Modifier.handCursor(),
        ) {
            service?.let { Icon(painter = painterResource(it.icon), contentDescription = null) }
            Spacer(Modifier.width(8.dp))
            Text(service?.displayName ?: apiKeyUrlDisplay)
        }
    } else {
        val linkColor = MaterialTheme.colorScheme.primary
        val copyApiKeyPromptString = stringResource(Res.string.settings_sign_in_copy_api_key_from)
        val annotatedString = remember(apiKeyUrl, apiKeyUrlDisplay) {
            buildAnnotatedString {
                append(copyApiKeyPromptString)
                append(" ")
                withLink(LinkAnnotation.Url(url = apiKeyUrl)) {
                    withStyle(style = SpanStyle(color = linkColor)) {
                        append(apiKeyUrlDisplay)
                    }
                }
            }
        }
        Text(
            annotatedString,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }

    Spacer(Modifier.height(16.dp))

    if (connectionStatus == ConnectionStatus.Connected || models.isNotEmpty()) {
        ModelSelection(selectedModel, models, onSelectModel, showExtendedSorts = service in listOf(Service.Polza, Service.Gptunnel, Service.AiTunnel, Service.PlusVibe, Service.PlusVibeReserve, Service.LlmRouter))
    }
}

@Composable
private fun OpenAICompatibleSettings(
    baseUrl: String,
    onChangeBaseUrl: (String) -> Unit,
    apiKey: String,
    onChangeApiKey: (String) -> Unit,
    selectedModel: SettingsModel?,
    models: ImmutableList<SettingsModel>,
    onSelectModel: (String) -> Unit,
    useCustomModel: Boolean,
    customModelId: String,
    onToggleUseCustomModel: (Boolean) -> Unit,
    onChangeCustomModelId: (String) -> Unit,
    connectionStatus: ConnectionStatus,
    onOpenAppPermissionSettings: () -> Unit = {},
) {
    PotsClearableTextField(
        value = baseUrl,
        onValueChange = onChangeBaseUrl,
        label = {
            Text(
                stringResource(Res.string.settings_base_url_label),
                color = MaterialTheme.colorScheme.onBackground,
            )
        },
        singleLine = true,
    )
    if (baseUrl.isNotBlank()) {
        Text(
            text = "${Service.normalizeOpenAICompatibleBaseUrl(baseUrl)}${Service.OpenAICompatible.chatUrl}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp),
        )
    }

    Spacer(Modifier.height(8.dp))

    ApiKeyField(
        apiKey = apiKey,
        onChangeApiKey = onChangeApiKey,
        labelText = stringResource(Res.string.settings_api_key_optional_label),
        singleLine = true,
    )

    Spacer(Modifier.height(8.dp))

    ConnectionStatusIndicator(connectionStatus, onOpenAppPermissionSettings)

    Spacer(Modifier.height(8.dp))

    val linkColor = MaterialTheme.colorScheme.primary
    val setupOllamaText = stringResource(Res.string.settings_openai_compatible_setup_ollama)
    val orOtherServiceText = stringResource(Res.string.settings_openai_compatible_or_other_service)
    val providersText = stringResource(Res.string.settings_openai_compatible_providers)
    val annotatedString = remember(setupOllamaText, orOtherServiceText, providersText, linkColor) {
        buildAnnotatedString {
            append(setupOllamaText)
            append(" ")
            withLink(LinkAnnotation.Url(url = "https://github.com/ollama/ollama")) {
                withStyle(style = SpanStyle(color = linkColor)) {
                    append("github.com/ollama/ollama")
                }
            }
            append(" ")
            append(orOtherServiceText)
            append(" ")
            withLink(LinkAnnotation.Url(url = "https://docs.litellm.ai/docs/providers")) {
                withStyle(style = SpanStyle(color = linkColor)) {
                    append(providersText)
                }
            }
        }
    }
    Text(
        annotatedString,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onBackground,
    )

    Spacer(Modifier.height(16.dp))

    if (connectionStatus == ConnectionStatus.Connected || models.isNotEmpty()) {
        ModelSelection(selectedModel, models, onSelectModel)
        Spacer(Modifier.height(8.dp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleUseCustomModel(!useCustomModel) }
            .handCursor(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = useCustomModel,
            onCheckedChange = onToggleUseCustomModel,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.settings_custom_model_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }

    if (useCustomModel) {
        Spacer(Modifier.height(8.dp))
        PotsClearableTextField(
            value = customModelId,
            onValueChange = onChangeCustomModelId,
            label = {
                Text(
                    stringResource(Res.string.settings_model_label),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            },
            singleLine = true,
        )
        Text(
            text = stringResource(Res.string.settings_custom_model_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp),
        )
    }
}

@Composable
private fun LiteRTSettings(
    selectedModel: SettingsModel?,
    downloadedModels: ImmutableList<SettingsModel>,
    availableModels: ImmutableList<LocalModel>,
    importedModels: ImmutableList<LocalModel>,
    totalDeviceMemoryBytes: Long,
    freeSpaceBytes: Long,
    downloadingModelId: String?,
    downloadProgress: Float?,
    downloadError: DownloadError?,
    importingFileName: String?,
    importProgress: Float?,
    importError: ModelImportError?,
    onSelectModel: (String) -> Unit,
    onDownloadModel: (LocalModel) -> Unit,
    onCancelDownload: () -> Unit,
    onImportModel: (PlatformFile) -> Unit,
    onCancelImport: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onChangeModelContextTokens: (String, Int) -> Unit,
    modelContextTokens: ImmutableMap<String, Int>,
) {
    val downloadedIds = remember(downloadedModels) { downloadedModels.map { it.id }.toSet() }
    val isBusy = downloadingModelId != null || importingFileName != null
    val isPreview = LocalInspectionMode.current

    val filePickerLauncher = if (!isPreview) {
        rememberFilePickerLauncher(
            type = FileKitType.File(extensions = listOf("litertlm")),
        ) { file ->
            if (file != null) onImportModel(file)
        }
    } else {
        null
    }

    Text(
        text = stringResource(Res.string.litert_on_device_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(4.dp))

    Text(
        text = stringResource(Res.string.litert_tool_support),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))

    Text(
        text = stringResource(Res.string.litert_import_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { filePickerLauncher?.launch() },
        modifier = Modifier.handCursor(),
        enabled = !isBusy && filePickerLauncher != null,
    ) {
        Text(stringResource(Res.string.litert_import))
    }

    if (importingFileName != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.litert_importing) + " $importingFileName",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (importProgress != null) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { importProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${(importProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onCancelImport,
                    modifier = Modifier.handCursor(),
                ) {
                    Text(
                        text = stringResource(Res.string.litert_cancel),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        } else {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }

    if (importError != null && importingFileName == null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                when (importError) {
                    ModelImportError.INVALID_EXTENSION -> Res.string.litert_error_import_invalid
                    ModelImportError.NOT_ENOUGH_DISK_SPACE -> Res.string.litert_error_not_enough_disk_space
                    ModelImportError.FILE_TOO_SMALL -> Res.string.litert_error_import_too_small
                    ModelImportError.COPY_FAILED -> Res.string.litert_error_import_failed
                    ModelImportError.CANCELLED -> Res.string.litert_error_import_failed
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(12.dp))

    availableModels.forEach { model ->
        LocalModelCard(
            model = model,
            isDownloaded = model.id in downloadedIds,
            isSelected = selectedModel?.id == model.id,
            isDownloading = downloadingModelId == model.id,
            downloadProgress = downloadProgress,
            isBusy = isBusy,
            totalDeviceMemoryBytes = totalDeviceMemoryBytes,
            modelContextTokens = modelContextTokens,
            onSelectModel = onSelectModel,
            onDownloadModel = onDownloadModel,
            onCancelDownload = onCancelDownload,
            onDeleteModel = onDeleteModel,
            onChangeModelContextTokens = onChangeModelContextTokens,
            showImportedBadge = false,
        )
    }

    if (importedModels.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.litert_imported),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        importedModels.forEach { model ->
            LocalModelCard(
                model = model,
                isDownloaded = true,
                isSelected = selectedModel?.id == model.id,
                isDownloading = false,
                downloadProgress = null,
                isBusy = isBusy,
                totalDeviceMemoryBytes = totalDeviceMemoryBytes,
                modelContextTokens = modelContextTokens,
                onSelectModel = onSelectModel,
                onDownloadModel = onDownloadModel,
                onCancelDownload = onCancelDownload,
                onDeleteModel = onDeleteModel,
                onChangeModelContextTokens = onChangeModelContextTokens,
                showImportedBadge = true,
            )
        }
    }

    if (downloadError != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                when (downloadError) {
                    DownloadError.NOT_ENOUGH_DISK_SPACE -> Res.string.litert_error_not_enough_disk_space
                    DownloadError.NETWORK_ERROR -> Res.string.litert_error_network
                    DownloadError.DOWNLOAD_INCOMPLETE -> Res.string.litert_error_download_incomplete
                    DownloadError.CHECKSUM_MISMATCH -> Res.string.litert_error_checksum_mismatch
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(8.dp))

    Text(
        text = stringResource(Res.string.litert_free_space, formatFileSize(freeSpaceBytes)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LocalModelCard(
    model: LocalModel,
    isDownloaded: Boolean,
    isSelected: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    isBusy: Boolean,
    totalDeviceMemoryBytes: Long,
    modelContextTokens: ImmutableMap<String, Int>,
    onSelectModel: (String) -> Unit,
    onDownloadModel: (LocalModel) -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onChangeModelContextTokens: (String, Int) -> Unit,
    showImportedBadge: Boolean,
) {
    val steps = (model.maxContextTokens - model.defaultContextTokens) / 1024
    val storedContextTokens = modelContextTokens[model.id] ?: model.defaultContextTokens
    var contextSliderValue by remember(storedContextTokens) {
        mutableStateOf(((storedContextTokens - model.defaultContextTokens) / 1024).toFloat())
    }
    val contextTokens = model.defaultContextTokens + (contextSliderValue.roundToInt() * 1024)
    val estimatedMemoryMb = estimateGpuMemoryMb(model, contextTokens)
    val performance = calculateDevicePerformance(totalDeviceMemoryBytes, estimatedMemoryMb)

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = if (isSelected) 3.dp else 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isDownloaded) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelectModel(model.id) },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            model.isRecommended ->
                                "${model.displayName} (${stringResource(Res.string.litert_recommended)})"

                            showImportedBadge ->
                                "${model.displayName} (${stringResource(Res.string.litert_imported)})"

                            else -> model.displayName
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatFileSize(model.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        DevicePerformanceLabel(performance)
                    }
                }
                if (isDownloaded) {
                    IconButton(
                        onClick = { onDeleteModel(model.id) },
                        modifier = Modifier.handCursor(),
                        enabled = !isBusy,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (!isDownloading) {
                    TextButton(
                        onClick = { onDownloadModel(model) },
                        modifier = Modifier.handCursor(),
                        enabled = !isBusy,
                    ) {
                        Text(stringResource(Res.string.litert_download))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.litert_context_size, "${contextTokens / 1024}K"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A model whose export tops out at its own default (LFM2.5) has nothing to
            // drag: a 0f..0f range divides by zero working out the thumb fraction. Show
            // the fixed size as a label and leave the slider out.
            if (steps > 0) {
                PotsSlider(
                    value = contextSliderValue,
                    onValueChange = { contextSliderValue = it },
                    onValueChangeFinished = {
                        onChangeModelContextTokens(model.id, contextTokens)
                    },
                    valueRange = 0f..steps.toFloat(),
                    steps = (steps - 1).coerceAtLeast(0),
                )
            }
            if (isDownloading && downloadProgress != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = onCancelDownload,
                        modifier = Modifier.handCursor(),
                    ) {
                        Text(
                            text = stringResource(Res.string.litert_cancel),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DevicePerformanceLabel(performance: DevicePerformance) {
    when (performance) {
        DevicePerformance.GOOD -> Text(
            text = stringResource(Res.string.litert_performance_good),
            style = MaterialTheme.typography.labelSmall,
            color = StatusColorConnected,
        )

        DevicePerformance.OK -> Text(
            text = stringResource(Res.string.litert_performance_ok),
            style = MaterialTheme.typography.labelSmall,
            color = StatusColorChecking,
        )

        DevicePerformance.POOR -> Text(
            text = stringResource(Res.string.litert_performance_poor),
            style = MaterialTheme.typography.labelSmall,
            color = StatusColorError,
        )
    }
}

@Composable
private fun ConnectionStatusIndicator(status: ConnectionStatus, onOpenAppPermissionSettings: () -> Unit = {}) {
    when (status) {
        ConnectionStatus.Unknown -> return

        ConnectionStatus.Checking -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.settings_status_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ConnectionStatus.Connected -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.settings_status_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        ConnectionStatus.ErrorQuotaExhausted -> {
            val warningColor = Color(0xFFFF9800)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = warningColor,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.settings_status_error_quota_exhausted),
                    style = MaterialTheme.typography.bodySmall,
                    color = warningColor,
                )
            }
        }

        ConnectionStatus.ErrorInvalidKey,
        ConnectionStatus.ErrorRateLimited,
        ConnectionStatus.ErrorConnectionFailed,
        ConnectionStatus.ErrorLocalNetworkDenied,
        ConnectionStatus.Error,
        -> {
            val errorMessage = when (status) {
                ConnectionStatus.ErrorInvalidKey -> stringResource(Res.string.settings_status_error_invalid_key)
                ConnectionStatus.ErrorRateLimited -> stringResource(Res.string.settings_status_error_rate_limited)
                ConnectionStatus.ErrorConnectionFailed -> stringResource(Res.string.settings_status_error_connection_failed)
                ConnectionStatus.ErrorLocalNetworkDenied -> stringResource(Res.string.settings_status_error_local_network)
                else -> stringResource(Res.string.settings_status_error)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (status == ConnectionStatus.ErrorLocalNetworkDenied) {
                TextButton(
                    onClick = onOpenAppPermissionSettings,
                    modifier = Modifier.handCursor(),
                ) {
                    Text(stringResource(Res.string.settings_open_app_settings))
                }
            }
        }
    }
}

@Composable
private fun ApiKeyField(
    apiKey: String,
    onChangeApiKey: (String) -> Unit,
    labelText: String,
    testTag: String? = null,
    singleLine: Boolean = false,
) {
    PotsClearableTextField(
        modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
        value = apiKey,
        onValueChange = onChangeApiKey,
        label = {
            Text(
                labelText,
                color = MaterialTheme.colorScheme.onBackground,
            )
        },
        singleLine = singleLine,
    )
}
