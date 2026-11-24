// Vault manager container (card 2/5): setup / unlock / list / detail / editor.
// The screen lives as a state inside the Settings tab; the "Password manager"
// menu entry itself is wired by card 4/5, navigation is not touched here.

package com.pusios.pots.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Token
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.vault.VaultBiometricUnlock
import com.pusios.pots.data.vault.VaultField
import com.pusios.pots.data.vault.VaultItem
import com.pusios.pots.data.vault.VaultItemKind
import com.pusios.pots.data.vault.VaultSession
import com.pusios.pots.data.vault.VaultTemplates
import com.pusios.pots.data.vault.kind
import com.pusios.pots.ui.PotsClearableTextField
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatformTools
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.vault_add_item
import pots.composeapp.generated.resources.vault_biometric_prompt_title
import pots.composeapp.generated.resources.vault_biometric_toggle
import pots.composeapp.generated.resources.vault_kind_all
import pots.composeapp.generated.resources.vault_kind_card
import pots.composeapp.generated.resources.vault_kind_host
import pots.composeapp.generated.resources.vault_kind_identity
import pots.composeapp.generated.resources.vault_kind_login
import pots.composeapp.generated.resources.vault_kind_note
import pots.composeapp.generated.resources.vault_kind_other
import pots.composeapp.generated.resources.vault_kind_ssh_key
import pots.composeapp.generated.resources.vault_kind_token
import pots.composeapp.generated.resources.vault_list_empty
import pots.composeapp.generated.resources.vault_list_empty_hint
import pots.composeapp.generated.resources.vault_lock_action
import pots.composeapp.generated.resources.vault_search_hint
import pots.composeapp.generated.resources.vault_title
import pots.composeapp.generated.resources.vault_unavailable

/** Root of the password manager: one screen per [VaultUiState] case. */
@Composable
fun VaultManagerScreen(
    session: VaultSession = koinInject(),
    modifier: Modifier = Modifier,
    biometric: VaultBiometricUnlock? = null,
) {
    if (!session.isSupported) {
        Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.vault_unavailable))
        }
        return
    }
    val scope = rememberCoroutineScope()
    val biometricUnlock = remember(biometric, session) { biometric ?: vaultBiometricOrNull() }
    val model = remember(session, biometricUnlock) { VaultViewModel(session, scope, biometricUnlock) }
    val state by model.state.collectAsState()
    val biometricHost = if (model.biometricAvailable) rememberVaultBiometricHost() else null
    val biometricPromptTitle = stringResource(Res.string.vault_biometric_prompt_title)

    when (val current = state) {
        VaultUiState.Unavailable -> Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(Res.string.vault_unavailable))
        }

        VaultUiState.Setup -> VaultSetupScreen(
            onSetup = { password -> scope.launch { model.setup(password) } },
            modifier = modifier,
        )

        is VaultUiState.Locked -> VaultUnlockScreen(
            error = current.error,
            onUnlock = { password -> scope.launch { model.unlock(password) } },
            modifier = modifier,
            biometricEnabled = current.biometricEnabled,
            onBiometricUnlock = if (current.biometricEnabled) {
                {
                    scope.launch { model.unlockBiometric(biometricHost, biometricPromptTitle) }
                }
            } else {
                null
            },
        )

        is VaultUiState.Unlocked -> VaultUnlockedContent(model, current, modifier)
    }
}

/** Optional DI lookup: null outside the app shell (tests, previews, no Koin). */
private fun vaultBiometricOrNull(): VaultBiometricUnlock? = runCatching { KoinPlatformTools.defaultContext().get().get<VaultBiometricUnlock>() }.getOrNull()

private sealed interface VaultRoute {
    data object List : VaultRoute

    data class Detail(val itemId: String) : VaultRoute

    data class Editor(val item: VaultItem) : VaultRoute
}

@Composable
internal fun VaultUnlockedContent(
    model: VaultViewModel,
    state: VaultUiState.Unlocked,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var route by remember { mutableStateOf<VaultRoute>(VaultRoute.List) }

    when (val current = route) {
        VaultRoute.List -> VaultListContent(
            model = model,
            state = state,
            modifier = modifier,
            onOpen = { route = VaultRoute.Detail(it) },
            onNew = { route = VaultRoute.Editor(newDraft()) },
        )

        is VaultRoute.Detail -> {
            val item = state.items.firstOrNull { it.id == current.itemId }
            if (item == null) {
                route = VaultRoute.List
            } else {
                VaultDetailScreen(
                    item = item,
                    onBack = { route = VaultRoute.List },
                    onEdit = { route = VaultRoute.Editor(item) },
                    onDelete = {
                        scope.launch {
                            model.delete(item.id)
                            route = VaultRoute.List
                        }
                    },
                    modifier = modifier,
                )
            }
        }

        is VaultRoute.Editor -> VaultItemEditorScreen(
            initial = current.item,
            onCancel = { route = VaultRoute.List },
            onSave = { saved ->
                scope.launch {
                    model.save(saved)
                    route = VaultRoute.List
                }
            },
            modifier = modifier,
        )
    }
}

/** A value-less card laid out per the chosen template; the store assigns the id. */
private fun newDraft(): VaultItem = VaultItem(
    id = "",
    type = VaultItemKind.LOGIN.id,
    name = "",
    fields = VaultTemplates.login.fields.map {
        VaultField(key = it.key, label = it.label, type = it.kind.id)
    },
)

/** Shared with the desktop UI tests, which click the FAB by tag. */
internal const val ADD_FAB_TAG = "vault-add-fab"

@Composable
private fun VaultListContent(
    model: VaultViewModel,
    state: VaultUiState.Unlocked,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var kindFilter by remember { mutableStateOf<VaultItemKind?>(null) }
    val biometricHost = if (model.biometricAvailable) rememberVaultBiometricHost() else null
    val biometricPromptTitle = stringResource(Res.string.vault_biometric_prompt_title)

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.vault_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { model.lock() }) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = stringResource(Res.string.vault_lock_action),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PotsClearableTextField(
                value = state.query,
                onValueChange = { query -> scope.launch { model.setQuery(query) } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.vault_search_hint)) },
                singleLine = true,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = kindFilter == null,
                    onClick = { kindFilter = null },
                    label = { Text(stringResource(Res.string.vault_kind_all)) },
                )
                VaultItemKind.entries.forEach { kind ->
                    FilterChip(
                        selected = kindFilter == kind,
                        onClick = { kindFilter = kind },
                        label = { Text(kindLabel(kind)) },
                    )
                }
            }
            if (model.biometricAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(Res.string.vault_biometric_toggle),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = state.biometricEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { model.setBiometricEnabled(enabled, biometricHost, biometricPromptTitle) }
                        },
                    )
                }
            }
            val activeFilter = kindFilter
            val items = state.items.filter { activeFilter == null || it.type == activeFilter.id }
            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(Res.string.vault_list_empty))
                        Text(
                            text = stringResource(Res.string.vault_list_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                VaultGroupedItemList(
                    items = items,
                    modifier = Modifier.weight(1f),
                    onOpen = onOpen,
                )
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag(ADD_FAB_TAG),
            icon = { Icon(imageVector = Icons.Outlined.Add, contentDescription = null) },
            text = { Text(stringResource(Res.string.vault_add_item)) },
        )
    }
}

/** Card list grouped by known kind; unknown raw types land in one trailing group. */
@Composable
private fun VaultGroupedItemList(
    items: List<VaultItem>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(items) {
        val known = VaultItemKind.entries.mapNotNull { kind ->
            val group = items.filter { it.type == kind.id }.sortedBy { it.name.lowercase() }
            if (group.isEmpty()) null else kind to group
        }
        val unknown = items.filter { VaultItemKind.fromId(it.type) == null }
        if (unknown.isEmpty()) known else known + (null to unknown)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { (kind, group) ->
            item(key = kind?.id ?: "unknown") {
                Text(
                    text = kind?.let { kindLabel(it) } ?: stringResource(Res.string.vault_kind_other),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(group, key = { it.id }) { item ->
                VaultItemRow(item = item, onOpen = { onOpen(item.id) })
            }
        }
    }
}

@Composable
private fun VaultItemRow(
    item: VaultItem,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = kindIcon(item),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = item.username.ifEmpty { item.url }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun kindLabel(kind: VaultItemKind) = when (kind) {
    VaultItemKind.LOGIN -> stringResource(Res.string.vault_kind_login)
    VaultItemKind.CARD -> stringResource(Res.string.vault_kind_card)
    VaultItemKind.IDENTITY -> stringResource(Res.string.vault_kind_identity)
    VaultItemKind.NOTE -> stringResource(Res.string.vault_kind_note)
    VaultItemKind.SSH_KEY -> stringResource(Res.string.vault_kind_ssh_key)
    VaultItemKind.HOST -> stringResource(Res.string.vault_kind_host)
    VaultItemKind.TOKEN -> stringResource(Res.string.vault_kind_token)
}

private fun kindIcon(item: VaultItem): ImageVector = when (item.kind) {
    VaultItemKind.LOGIN -> Icons.Outlined.Key
    VaultItemKind.CARD -> Icons.Outlined.CreditCard
    VaultItemKind.IDENTITY -> Icons.Outlined.Badge
    VaultItemKind.NOTE -> Icons.Outlined.Description
    VaultItemKind.SSH_KEY -> Icons.Outlined.VpnKey
    VaultItemKind.HOST -> Icons.Outlined.Dns
    VaultItemKind.TOKEN -> Icons.Outlined.Token
    null -> Icons.Outlined.Folder
}
