// Quick unlock ViewModel tests (card t_6b300431): a fake VaultBiometricUnlock
// records enable/disable/prompt calls over a real temp-dir vault session. The
// fake ignores the host — the Android-only FragmentActivity anchor is covered
// by the platform actuals, not here. State transitions ride the real
// session.locked collector on Dispatchers.Default, so assertions poll instead
// of racing.

package com.pusios.pots.ui.vault

import com.pusios.pots.data.vault.JvmVaultCryptoEngine
import com.pusios.pots.data.vault.JvmVaultFileAccess
import com.pusios.pots.data.vault.VaultBiometricUnlock
import com.pusios.pots.data.vault.VaultSession
import com.pusios.pots.data.vault.VaultStore
import com.pusios.pots.data.vault.VaultTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeVaultBiometric : VaultBiometricUnlock {
    override val isAvailable: Boolean = true

    override val isEnabled: Boolean
        get() = blob != null

    var blob: ByteArray? = null
    var promptReturnsKey: Boolean = true
    var disableCalls: Int = 0

    override suspend fun enable(vaultKey: ByteArray, host: VaultBiometricHost?, promptTitle: String): Boolean {
        blob = vaultKey.copyOf()
        return true
    }

    override suspend fun promptForVaultKey(host: VaultBiometricHost?, promptTitle: String): ByteArray? =
        if (promptReturnsKey) blob else null

    override fun disable() {
        disableCalls += 1
        blob = null
    }
}

class VaultViewModelBiometricTest {

    private fun tempDir(): File = Files.createTempDirectory("vault-biometric-test").toFile()

    private fun newSession(dir: File): VaultSession = VaultSession(
        store = VaultStore(JvmVaultFileAccess(dir), JvmVaultCryptoEngine()),
    )

    /** Wait for the collector-driven state to settle; returns the settled state. */
    private fun waitUntil(
        model: VaultViewModel,
        timeoutMillis: Long = 5000,
        condition: (VaultUiState) -> Boolean,
    ): VaultUiState {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            val current = model.state.value
            if (condition(current)) return current
            Thread.sleep(10)
        }
        throw AssertionError("state did not reach the expected screen within $timeoutMillis ms: ${model.state.value}")
    }

    @Test
    fun `enable toggle stores the key and quick unlock reopens after lock`() = runBlocking {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        session.upsert(VaultTemplates.draft("login", name = "GitHub")!!)
        val biometric = FakeVaultBiometric()
        val model = VaultViewModel(session, CoroutineScope(Dispatchers.Default), biometric)
        waitUntil(model) { it is VaultUiState.Unlocked }
        assertTrue(model.biometricAvailable)

        model.setBiometricEnabled(enabled = true, host = null, promptTitle = "t")
        waitUntil(model) { it is VaultUiState.Unlocked && it.biometricEnabled }
        assertTrue(biometric.isEnabled)

        model.lock()
        waitUntil(model) { it is VaultUiState.Locked && it.biometricEnabled }

        model.unlockBiometric(host = null, promptTitle = "t")
        val reopened = waitUntil(model) { it is VaultUiState.Unlocked && it.items.isNotEmpty() }
        assertEquals("GitHub", (reopened as VaultUiState.Unlocked).items.single().name)
        assertTrue(biometric.isEnabled)
        assertEquals(0, biometric.disableCalls)
    }

    @Test
    fun `declined prompt keeps the lock screen`() = runBlocking {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        val biometric = FakeVaultBiometric()
        biometric.promptReturnsKey = false
        val model = VaultViewModel(session, CoroutineScope(Dispatchers.Default), biometric)
        waitUntil(model) { it is VaultUiState.Unlocked }

        model.setBiometricEnabled(enabled = true, host = null, promptTitle = "t")
        waitUntil(model) { it is VaultUiState.Unlocked && it.biometricEnabled }
        model.lock()
        waitUntil(model) { it is VaultUiState.Locked && it.biometricEnabled }

        model.unlockBiometric(host = null, promptTitle = "t")

        val locked = model.state.value as VaultUiState.Locked
        assertTrue(locked.biometricEnabled)
        assertEquals(0, biometric.disableCalls)
    }

    @Test
    fun `stale blob is dropped and the toggle turns off`() = runBlocking {
        val dir = tempDir()
        val session = newSession(dir)
        session.createVault("master-1")
        val biometric = FakeVaultBiometric()
        val model = VaultViewModel(session, CoroutineScope(Dispatchers.Default), biometric)
        waitUntil(model) { it is VaultUiState.Unlocked }

        model.setBiometricEnabled(enabled = true, host = null, promptTitle = "t")
        waitUntil(model) { it is VaultUiState.Unlocked && it.biometricEnabled }
        model.lock()
        waitUntil(model) { it is VaultUiState.Locked && it.biometricEnabled }

        // Simulate a blob the device key no longer opens: the vault was
        // recreated with a different key, so the payload GCM check fails.
        biometric.blob = ByteArray(32)

        model.unlockBiometric(host = null, promptTitle = "t")
        val locked = waitUntil(model) { it is VaultUiState.Locked && !it.biometricEnabled }
        assertFalse((locked as VaultUiState.Locked).biometricEnabled)
        assertTrue(biometric.disableCalls >= 1)
    }

    @Test
    fun `setup of a fresh vault drops any stale quick-unlock blob`() = runBlocking {
        val dir = tempDir()
        val session = newSession(dir)
        val biometric = FakeVaultBiometric()
        biometric.blob = ByteArray(32)
        val model = VaultViewModel(session, CoroutineScope(Dispatchers.Default), biometric)

        model.setup("master-1")

        waitUntil(model) { it is VaultUiState.Unlocked }
        assertNull(biometric.blob)
        assertTrue(biometric.disableCalls >= 1)
    }
}
