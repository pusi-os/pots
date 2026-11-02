package com.pusios.pots.data.miniapp

import com.pusios.pots.data.AppSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MiniAppStandaloneValidatorTest {
    @Test
    fun localActionsAreStandalone() {
        val check = MiniAppStandaloneValidator.check(
            listOf(
                MiniAppScreen(
                    id = "home",
                    potsUiJson = """{"type":"column","children":[{"type":"button","action":{"type":"store_put","collection":"tasks"}},{"type":"button","action":{"type":"navigate","screenId":"done"}}]}""",
                ),
            ),
        )
        assertTrue(check.ready)
    }

    @Test
    fun callbackIsNotStandalone() {
        val check = MiniAppStandaloneValidator.check(
            listOf(MiniAppScreen("home", potsUiJson = """{"type":"button","action":{"type":"callback","event":"ask-agent"}}""")),
        )
        assertFalse(check.ready)
        assertTrue(check.reasons.any { it.contains("callback") })
    }

    @Test
    fun unknownHostServiceIsNotStandalone() {
        val check = MiniAppStandaloneValidator.check(
            listOf(MiniAppScreen("home", potsUiJson = """{"type":"button","action":{"type":"host_call","service":"network.fetch"}}""")),
        )
        assertFalse(check.ready)
        assertTrue(check.reasons.any { it.contains("unsupported host service") })
    }

    @Test
    fun savedNativeAppComputesStandaloneStatus() {
        val settings = AppSettings(com.russhwolf.settings.MapSettings())
        val repository = MiniAppRepository(settings, SettingsMiniAppPersistence(settings))
        val saved = repository.saveDraft(
            MiniAppDraft(
                id = "standalone",
                name = "Standalone",
                screens = listOf(MiniAppScreen("home", potsUiJson = """{"type":"button","action":{"type":"navigate","screenId":"home"}}""")),
            ),
        )
        assertTrue(saved.standaloneReady)
    }
}
