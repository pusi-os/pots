// Vault request sheet tests (card 2/5, review round 2): the secrets-safety
// criterion — entered values live in plain `remember`, so composition
// recreation drops them; nothing is wired into rememberSaveable, so a
// recreated composition cannot contain any marker of the typed secret.
// The sheet body composes here without a bridge; the host itself wraps a
// ModalBottomSheet (an AWT dialog on desktop), which cannot run headless.

package com.pusios.pots.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.pusios.pots.data.vault.VaultFieldInput
import com.pusios.pots.data.vault.VaultFormFieldSpec
import com.pusios.pots.data.vault.VaultFormRequest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class VaultRequestSheetTest {

    private fun form() = VaultFormRequest(
        id = "form-1",
        mode = "create",
        reason = "I need the token to set up CI in your repository",
        fields = listOf(
            VaultFormFieldSpec(key = "username", label = "Username", input = VaultFieldInput.TEXT),
            VaultFormFieldSpec(key = "password", label = "Password", input = VaultFieldInput.SECRET, required = true),
        ),
    )

    @Test
    fun `typed values are not in saved state and vanish on composition recreation`() = runComposeUiTest {
        // EditableText carries the raw value even behind the password mask, so
        // the field semantics are the only place the secret can be detected.
        fun typedEditableTexts(): List<String> = onAllNodes(hasSetTextAction())
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.EditableText].text }

        setContent {
            MaterialTheme {
                VaultRequestSheetContent(form = form(), stage = VaultSheetStage.Form())
            }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("alice")
        onAllNodes(hasSetTextAction())[1].performTextInput("s3cret-42")
        // The values are in composable state — and only there.
        assertTrue(typedEditableTexts().any { "s3cret-42" in it })

        // Simulated activity recreation: a fresh composition. rememberSaveable
        // would restore the values here; plain remember must not.
        setContent {
            MaterialTheme {
                VaultRequestSheetContent(form = form(), stage = VaultSheetStage.Form())
            }
        }
        val restored = typedEditableTexts()
        assertTrue(restored.isNotEmpty())
        assertTrue(restored.all { it.isEmpty() }, "values leaked into saved state: $restored")
    }
}
