package com.pusios.pots.ui.chat.composables

import com.pusios.pots.network.NoConfiguredServiceException
import com.pusios.pots.network.UiError
import com.pusios.pots.network.toUiError
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.error_no_service_body
import pots.composeapp.generated.resources.error_no_service_title
import pots.composeapp.generated.resources.error_unknown
import kotlin.test.Test
import kotlin.test.assertEquals

// t_eaa0f8f6: the panel's only honest state is "inference is not configured".
// The free-tier copy ("Free tier limit reached", Free FAST/EXPERT) must not come back.
class FreeProviderSuggestionsPanelCopyTest {

    @Test
    fun `no configured service exception resolves to no-service copy`() {
        val error = NoConfiguredServiceException().toUiError()

        assertEquals(
            Res.string.error_no_service_title to Res.string.error_no_service_body,
            freeProviderSuggestionsPanelCopy(error),
        )
    }

    @Test
    fun `every error that reaches the panel resolves to the same single state`() {
        val expected = Res.string.error_no_service_title to Res.string.error_no_service_body

        assertEquals(expected, freeProviderSuggestionsPanelCopy(UiError.Resource(Res.string.error_unknown)))
        assertEquals(expected, freeProviderSuggestionsPanelCopy(UiError.Text("boom")))
    }
}
