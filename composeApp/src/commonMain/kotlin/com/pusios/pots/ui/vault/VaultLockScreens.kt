// Vault lock screens (card 2/5): first-run master password setup and the
// unlock screen shown whenever the session is closed. Passwords live in plain
// `remember` state — never in `rememberSaveable`, so process death and
// configuration changes drop them instead of persisting secrets.

package com.pusios.pots.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pusios.pots.ui.PotsOutlinedTextField
import org.jetbrains.compose.resources.stringResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.vault_error_corrupted
import pots.composeapp.generated.resources.vault_error_tampered
import pots.composeapp.generated.resources.vault_error_unsupported_version
import pots.composeapp.generated.resources.vault_error_wrong_password
import pots.composeapp.generated.resources.vault_password_mismatch
import pots.composeapp.generated.resources.vault_setup_confirm
import pots.composeapp.generated.resources.vault_setup_create
import pots.composeapp.generated.resources.vault_setup_hint
import pots.composeapp.generated.resources.vault_setup_password
import pots.composeapp.generated.resources.vault_setup_title
import pots.composeapp.generated.resources.vault_setup_warning
import pots.composeapp.generated.resources.vault_unlock_button
import pots.composeapp.generated.resources.vault_unlock_hint
import pots.composeapp.generated.resources.vault_unlock_password
import pots.composeapp.generated.resources.vault_unlock_title

/** First-run setup: choose the master password; there is no recovery. */
@Composable
internal fun VaultSetupScreen(
    onSetup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = password.isNotEmpty() && confirm.isNotEmpty() && password != confirm

    VaultAuthLayout(modifier = modifier) {
        Text(
            text = stringResource(Res.string.vault_setup_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(Res.string.vault_setup_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        PotsOutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.vault_setup_password)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        PotsOutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.vault_setup_confirm)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        if (mismatch) {
            Text(
                text = stringResource(Res.string.vault_password_mismatch),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = stringResource(Res.string.vault_setup_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { onSetup(password) },
            enabled = password.isNotEmpty() && password == confirm,
        ) {
            Text(stringResource(Res.string.vault_setup_create))
        }
    }
}

/** Unlock screen; [error] names why the previous attempt failed. Public so
 * paparazzi can compose the real vault tab without a session or a Koin
 * container. [biometricEnabled] + [onBiometricUnlock] offer the device-bound
 * quick unlock (card t_6b300431); defaults keep the old signature valid. */
@Composable
public fun VaultUnlockScreen(
    error: VaultUnlockError?,
    onUnlock: (String) -> Unit,
    modifier: Modifier = Modifier,
    biometricEnabled: Boolean = false,
    onBiometricUnlock: (() -> Unit)? = null,
) {
    var password by remember { mutableStateOf("") }

    VaultAuthLayout(modifier = modifier) {
        Text(
            text = stringResource(Res.string.vault_unlock_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(Res.string.vault_unlock_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        PotsOutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.vault_unlock_password)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        if (error != null) {
            Text(
                text = stringResource(error.messageResource()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = { onUnlock(password) },
            enabled = password.isNotEmpty(),
        ) {
            Text(stringResource(Res.string.vault_unlock_button))
        }
        if (biometricEnabled && onBiometricUnlock != null) {
            TextButton(onClick = onBiometricUnlock) {
                Icon(
                    imageVector = Icons.Outlined.Fingerprint,
                    contentDescription = null,
                )
                Text(stringResource(Res.string.vault_biometric_unlock_button))
            }
        }
    }
}

@Composable
private fun VaultUnlockError.messageResource() = when (this) {
    VaultUnlockError.WRONG_PASSWORD -> Res.string.vault_error_wrong_password
    VaultUnlockError.TAMPERED -> Res.string.vault_error_tampered
    VaultUnlockError.CORRUPTED -> Res.string.vault_error_corrupted
    VaultUnlockError.UNSUPPORTED_VERSION -> Res.string.vault_error_unsupported_version
}

@Composable
private fun VaultAuthLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}
