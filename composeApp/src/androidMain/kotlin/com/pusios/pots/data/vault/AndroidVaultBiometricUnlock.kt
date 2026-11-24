// Vault quick unlock on Android (card t_6b300431): the vault key is re-wrapped
// under a hardware-backed Keystore key that requires user authentication for
// every crypto operation. The wrap blob (IV || ciphertext) lives in the
// encrypted prefs; the master password never touches this path. Android 11+
// only: setUserAuthenticationParameters with AUTH_DEVICE_CREDENTIAL provides
// the PIN fallback, and DEVICE_CREDENTIAL keeps the whole authentication
// (including the negative path) inside one system dialog.

package com.pusios.pots.data.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.pusios.pots.ui.vault.VaultBiometricHost
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

class AndroidVaultBiometricUnlock(
    private val settings: Settings,
    context: Context,
) : VaultBiometricUnlock {

    private val biometricManager = BiometricManager.from(context)
    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    override val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    override val isEnabled: Boolean
        get() = settings.hasKey(BLOB_KEY)

    override suspend fun enable(vaultKey: ByteArray, host: VaultBiometricHost?, promptTitle: String): Boolean {
        val activity = host ?: return false
        return withContext(Dispatchers.Main) {
            runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, deviceKey())
                }
                val authenticated = authenticate(activity, cipher, promptTitle) ?: return@runCatching false
                val wrapped = authenticated.doFinal(vaultKey)
                settings.putString(BLOB_KEY, Base64.getEncoder().encodeToString(authenticated.iv + wrapped))
                true
            }.getOrDefault(false)
        }
    }

    override suspend fun promptForVaultKey(host: VaultBiometricHost?, promptTitle: String): ByteArray? {
        val activity = host ?: return null
        val encoded = settings.getString(BLOB_KEY, "")
        if (encoded.isEmpty()) return null
        return withContext(Dispatchers.Main) {
            runCatching {
                val raw = Base64.getDecoder().decode(encoded)
                val iv = raw.copyOfRange(0, GCM_IV_BYTES)
                val ciphertext = raw.copyOfRange(GCM_IV_BYTES, raw.size)
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                }
                val authenticated = authenticate(activity, cipher, promptTitle) ?: return@runCatching null
                authenticated.doFinal(ciphertext)
            }.getOrElse {
                // The device key no longer opens the blob (biometric
                // re-enrollment with invalidation, key removed, vault
                // recreated): quick unlock is dead until the master password
                // re-enables it.
                disable()
                null
            }
        }
    }

    override fun disable() {
        settings.remove(BLOB_KEY)
    }

    private fun deviceKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                // 0 + explicit authenticators = authentication per operation:
                // the blob never decrypts without a live confirmation.
                .setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                )
                .setInvalidatedByBiometricEnrollment(false)
                .build(),
        )
        return generator.generateKey()
    }

    private suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        promptTitle: String,
    ): Cipher? = suspendCancellableCoroutine { continuation ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(null)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(promptTitle)
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "pots_vault_quick_unlock"
        const val BLOB_KEY = "vault_biometric_wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
    }
}
