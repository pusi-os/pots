// The settings model's decisions: pairing success is what makes a device trusted, an address
// outside the LAN never reaches the backend, and the trust switch is persisted. The mDNS scan
// itself needs the platform permission, so it is exercised on a device, not here.

package com.pusios.pots.ui.settings

import com.pusios.pots.adb.AdbPairResult
import com.pusios.pots.adb.FakeAdbBackend
import com.pusios.pots.adb.FakeAdbStore
import com.pusios.pots.adb.TEST_DEVICE
import com.pusios.pots.tools.AppPermission
import com.pusios.pots.tools.PermissionController
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun model(
    scope: TestScope,
    backend: FakeAdbBackend = FakeAdbBackend(),
    store: FakeAdbStore = FakeAdbStore(emptyList()),
) = AdbSettingsViewModel(backend, store, PermissionController(AppPermission.LOCAL_NETWORK), scope)

class AdbSettingsViewModelTest {

    @Test
    fun pairingSuccessStoresTheDeviceTrustedAndConnects() = runTest {
        val backend = FakeAdbBackend().apply { shellOutput = "Pixel 8\n" }
        val store = FakeAdbStore(emptyList())
        val model = model(this, backend, store)
        model.onEndpointChange("192.168.1.50:43125")
        model.onPairingPortChange("37123")
        model.onCodeChange("123456")
        model.onPairAndConnect()
        advanceUntilIdle()

        assertEquals(listOf("192.168.1.50:37123"), backend.pairs)
        val stored = store.all().single()
        assertEquals("192.168.1.50:43125", stored.id)
        assertTrue(stored.trusted)
        assertTrue(stored.tls)
        // The model is read once after connect, so adb_list_devices has something to show.
        assertEquals("Pixel 8", stored.lastModel)
        assertEquals(listOf("192.168.1.50:43125"), backend.connects)
        assertTrue(model.state.value.notice is AdbNotice.Connected)
        assertFalse(model.state.value.busy)
        assertEquals("", model.state.value.code)
    }

    @Test
    fun aWrongCodeStoresNothingAndSaysSo() = runTest {
        val backend = FakeAdbBackend().apply { pairResult = AdbPairResult.InvalidCode }
        val store = FakeAdbStore(emptyList())
        val model = model(this, backend, store)
        model.onEndpointChange("192.168.1.50:43125")
        model.onPairingPortChange("37123")
        model.onCodeChange("000000")
        model.onPairAndConnect()
        advanceUntilIdle()

        assertEquals(AdbNotice.InvalidCode, model.state.value.notice)
        assertTrue(store.all().isEmpty())
        assertTrue(backend.connects.isEmpty())
    }

    @Test
    fun aPublicAddressNeverReachesTheBackend() = runTest {
        val backend = FakeAdbBackend()
        val model = model(this, backend)
        model.onEndpointChange("203.0.113.7:5555")
        model.onConnect()
        advanceUntilIdle()

        assertEquals(AdbNotice.EndpointNotPrivate, model.state.value.notice)
        assertTrue(backend.connects.isEmpty())
    }

    @Test
    fun connectingAnUnknownDeviceStoresItUntrusted() = runTest {
        val backend = FakeAdbBackend()
        val store = FakeAdbStore(emptyList())
        val model = model(this, backend, store)
        model.onEndpointChange("192.168.1.77:5555")
        model.onConnect()
        advanceUntilIdle()

        val stored = store.all().single()
        assertFalse(stored.trusted)
        assertFalse(stored.tls)
        assertTrue(model.state.value.notice is AdbNotice.Connected)
    }

    @Test
    fun theTrustSwitchIsPersisted() = runTest {
        val store = FakeAdbStore(listOf(TEST_DEVICE))
        val model = model(this, store = store)
        model.onTrustChange(TEST_DEVICE, false)
        assertFalse(store.all().single().trusted)
        assertFalse(model.state.value.devices.single().trusted)
    }

    @Test
    fun removingADeviceClosesItsSessionFirst() = runTest {
        val backend = FakeAdbBackend()
        val store = FakeAdbStore(listOf(TEST_DEVICE))
        val model = model(this, backend, store)
        model.onRemove(TEST_DEVICE)
        advanceUntilIdle()

        assertEquals(listOf("192.168.1.50:5555"), backend.disconnects)
        assertTrue(store.all().isEmpty())
        assertNull(model.state.value.notice)
    }
}