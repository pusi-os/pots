// Vault credential request sheet (card 2/5, §6.1): the chat-side UI for the
// agent's `vault_request_form` tool. The tool publishes a form description via
// [VaultFormBridge.pending]; the sheet renders it from the typed model, takes
// the user's values and hands them to [VaultFormBridge.submit] — the only path
// values ever travel. Values live in plain `remember` — never
// `rememberSaveable` — so nothing lands in saved instance state, and no value
// becomes a chat message: after save the sheet itself shows the confirmation.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.pusios.pots.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pusios.pots.data.vault.VaultFieldInput
import com.pusios.pots.data.vault.VaultFormBridge
import com.pusios.pots.data.vault.VaultFormFieldSpec
import com.pusios.pots.data.vault.VaultFormOutcome
import com.pusios.pots.data.vault.VaultFormRequest
import com.pusios.pots.data.vault.VaultSession
import com.pusios.pots.data.vault.VaultUnlockResult
import com.pusios.pots.data.vault.createVaultCryptoEngine
import com.pusios.pots.ui.PotsOutlinedTextField
import com.pusios.pots.ui.SecureWindowFlag
import com.pusios.pots.ui.vault.VaultUnlockError
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.vault_common_cancel
import pots.composeapp.generated.resources.vault_common_save
import pots.composeapp.generated.resources.vault_error_corrupted
import pots.composeapp.generated.resources.vault_error_tampered
import pots.composeapp.generated.resources.vault_error_unsupported_version
import pots.composeapp.generated.resources.vault_error_wrong_password
import pots.composeapp.generated.resources.vault_form_close
import pots.composeapp.generated.resources.vault_form_generate
import pots.composeapp.generated.resources.vault_form_locked_hint
import pots.composeapp.generated.resources.vault_form_no_vault
import pots.composeapp.generated.resources.vault_form_reason
import pots.composeapp.generated.resources.vault_form_required
import pots.composeapp.generated.resources.vault_form_saved
import pots.composeapp.generated.resources.vault_title
import pots.composeapp.generated.resources.vault_toggle_hide
import pots.composeapp.generated.resources.vault_toggle_show
import pots.composeapp.generated.resources.vault_unlock_button
import pots.composeapp.generated.resources.vault_unlock_password

/** What stage of the request the sheet is showing. Public so screenshot tests
 * (another module) can compose the stages without a bridge. */
public sealed interface VaultSheetStage {
    /** The form itself; [error] names the last failed submit, if any. */
    data class Form(val error: String? = null) : VaultSheetStage

    /** The vault is locked; [error] and [noVault] name the unlock situation. */
    data class Unlock(val error: VaultUnlockError? = null, val noVault: Boolean = false) : VaultSheetStage

    /** The values are in the vault; the tool call has been answered. */
    data class Saved(val itemName: String) : VaultSheetStage
}

/**
 * Hosts the request sheet for as long as [VaultFormBridge.pending] carries a
 * form. [conversationId] scopes the submit: a sheet left open from another
 * conversation must not answer this conversation's tool call.
 */
@Composable
fun VaultRequestSheetHost(
    bridge: VaultFormBridge,
    session: VaultSession,
    conversationId: String?,
) {
    val request by bridge.pending.collectAsState()
    val form = request ?: return
    val scope = rememberCoroutineScope()
    val locked by session.locked.collectAsState()

    var savedName by remember(form.id) { mutableStateOf<String?>(null) }
    var submitError by remember(form.id) { mutableStateOf<String?>(null) }
    var unlockError by remember(form.id) { mutableStateOf<VaultUnlockError?>(null) }
    var noVault by remember(form.id) { mutableStateOf(false) }

    val saved = savedName
    val stage = when {
        saved != null -> VaultSheetStage.Saved(saved)
        locked -> VaultSheetStage.Unlock(unlockError, noVault)
        else -> VaultSheetStage.Form(submitError)
    }

    ModalBottomSheet(onDismissRequest = { bridge.cancel(form.id) }) {
        VaultRequestSheetContent(
            form = form,
            stage = stage,
            onUnlock = { password ->
                scope.launch {
                    when (session.unlock(password)) {
                        VaultUnlockResult.Success -> {
                            noVault = false
                            unlockError = null
                        }

                        VaultUnlockResult.NotCreated -> noVault = true

                        VaultUnlockResult.WrongPassword -> unlockError = VaultUnlockError.WRONG_PASSWORD

                        VaultUnlockResult.Tampered -> unlockError = VaultUnlockError.TAMPERED

                        VaultUnlockResult.Corrupted -> unlockError = VaultUnlockError.CORRUPTED

                        is VaultUnlockResult.UnsupportedVersion ->
                            unlockError = VaultUnlockError.UNSUPPORTED_VERSION

                        VaultUnlockResult.NotSupported -> unlockError = null
                    }
                }
            },
            onSubmit = { values ->
                scope.launch {
                    when (val outcome = bridge.submit(form.id, conversationId, values)) {
                        is VaultFormOutcome.Saved -> savedName = outcome.itemName
                        VaultFormOutcome.Locked -> { /* the locked flow picks the stage up */ }
                        is VaultFormOutcome.Failed -> submitError = outcome.reason
                        VaultFormOutcome.Cancelled -> bridge.cancel(form.id)
                    }
                }
            },
            onCancel = { bridge.cancel(form.id) },
        )
    }
}

/** Dumb sheet body: renders one [VaultSheetStage] of the given form. Public so
 * screenshot tests (another module) can compose the stages without a bridge. */
@Composable
public fun VaultRequestSheetContent(
    form: VaultFormRequest,
    stage: VaultSheetStage,
    initialValues: Map<String, String> = emptyMap(),
    onUnlock: (String) -> Unit = {},
    onSubmit: (Map<String, String>) -> Unit = {},
    onCancel: () -> Unit = {},
) {
    SecureWindowFlag(enabled = true)
    val reason = stringResource(Res.string.vault_form_reason, form.reason)
    val badge = stringResource(Res.string.vault_title)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = {}, label = { Text(badge) })
        Text(reason, style = MaterialTheme.typography.titleMedium)
        if (form.name.isNotBlank()) {
            Text(form.name, style = MaterialTheme.typography.bodyMedium)
        }

        when (stage) {
            is VaultSheetStage.Form -> VaultSheetFormStage(
                form = form,
                error = stage.error,
                initialValues = initialValues,
                onSubmit = onSubmit,
                onCancel = onCancel,
            )

            is VaultSheetStage.Unlock -> VaultSheetUnlockStage(
                error = stage.error,
                noVault = stage.noVault,
                onUnlock = onUnlock,
                onCancel = onCancel,
            )

            is VaultSheetStage.Saved -> {
                Text(stringResource(Res.string.vault_form_saved, stage.itemName))
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(Res.string.vault_form_close))
                }
            }
        }
    }
}

@Composable
private fun VaultSheetFormStage(
    form: VaultFormRequest,
    error: String?,
    initialValues: Map<String, String>,
    onSubmit: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    val generateLabel = stringResource(Res.string.vault_form_generate)
    // Plain remember — the values must not survive process death via
    // rememberSaveable, and they never leave the sheet in any other way.
    val values = remember(form.id) { mutableStateMapOf<String, String>().apply { putAll(initialValues) } }
    val requiredMissing = form.fields.any { it.required && values[it.key].isNullOrBlank() }

    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        form.fields.forEach { spec ->
            VaultSheetFieldRow(
                spec = spec,
                value = values[spec.key].orEmpty(),
                allowGenerate = form.allowGenerate,
                onValueChange = { values[spec.key] = it },
                onGenerate = {
                    createVaultCryptoEngine()?.let { engine ->
                        values[spec.key] = engine.encodeBase64(engine.randomBytes(24))
                    }
                },
                generateLabel = generateLabel,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(Res.string.vault_common_cancel))
            }
            Button(
                onClick = { onSubmit(values.toMap()) },
                enabled = !requiredMissing,
            ) {
                Text(stringResource(Res.string.vault_common_save))
            }
        }
    }
}

@Composable
private fun VaultSheetFieldRow(
    spec: VaultFormFieldSpec,
    value: String,
    allowGenerate: Boolean,
    onValueChange: (String) -> Unit,
    onGenerate: () -> Unit,
    generateLabel: String,
) {
    val masked = spec.input == VaultFieldInput.SECRET || spec.input == VaultFieldInput.SECRET_MULTILINE
    var visible by remember(spec.key) { mutableStateOf(false) }
    val multiline = spec.input == VaultFieldInput.MULTILINE || spec.input == VaultFieldInput.SECRET_MULTILINE

    Column(modifier = Modifier.fillMaxWidth()) {
        PotsOutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(spec.label) },
            placeholder = spec.placeholder?.let { { Text(it) } },
            visualTransformation = if (masked && !visible) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            // Autocorrection off for everything an agent asked for: the sheet
            // types credentials, not prose. Secret fields use the password
            // keyboard; autofill semantics are deliberately not set — the
            // platform must not offer to save what is typed here.
            keyboardOptions = KeyboardOptions(
                autoCorrect = false,
                capitalization = KeyboardCapitalization.None,
                keyboardType = when {
                    spec.input == VaultFieldInput.URL -> KeyboardType.Uri
                    masked -> KeyboardType.Password
                    else -> KeyboardType.Text
                },
            ),
            trailingIcon = if (masked) {
                {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = stringResource(
                                if (visible) Res.string.vault_toggle_hide else Res.string.vault_toggle_show,
                            ),
                        )
                    }
                }
            } else {
                null
            },
            singleLine = !multiline,
            minLines = 1,
            maxLines = if (multiline) 6 else 1,
        )
        if (allowGenerate && masked) {
            TextButton(onClick = onGenerate) {
                Text(generateLabel)
            }
        }
        if (spec.required) {
            Text(
                text = stringResource(Res.string.vault_form_required),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VaultSheetUnlockStage(
    error: VaultUnlockError?,
    noVault: Boolean,
    onUnlock: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (noVault) {
            Text(stringResource(Res.string.vault_form_no_vault))
        } else {
            Text(stringResource(Res.string.vault_form_locked_hint))
        }
        error?.let { Text(unlockErrorText(it), color = MaterialTheme.colorScheme.error) }
        PotsOutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.vault_unlock_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrect = false,
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Password,
            ),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(Res.string.vault_common_cancel))
            }
            Button(
                onClick = {
                    onUnlock(password)
                    password = ""
                },
                enabled = password.isNotBlank(),
            ) {
                Text(stringResource(Res.string.vault_unlock_button))
            }
        }
    }
}

@Composable
private fun unlockErrorText(error: VaultUnlockError): String = stringResource(
    when (error) {
        VaultUnlockError.WRONG_PASSWORD -> Res.string.vault_error_wrong_password
        VaultUnlockError.TAMPERED -> Res.string.vault_error_tampered
        VaultUnlockError.CORRUPTED -> Res.string.vault_error_corrupted
        VaultUnlockError.UNSUPPORTED_VERSION -> Res.string.vault_error_unsupported_version
    },
)
