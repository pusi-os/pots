// Vault request sheet tests (card 2/5, review round 2): the secrets-safety
// criterion — entered values live in plain `remember`, so composition
// recreation drops them; nothing is wired into rememberSaveable, so a
// recreated composition cannot contain any marker of the typed secret.
// The sheet body composes here without a bridge; the host itself wraps a
// ModalBottomSheet (an AWT dialog on desktop), which cannot run headless.

package com.pusios.pots.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.pusios.pots.data.vault.VaultFieldInput
import com.pusios.pots.data.vault.VaultFormFieldSpec
import com.pusios.pots.data.vault.VaultFormRequest
import kotlin.test.Test
import kotlin.test.assertEquals
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

        // The sheet is fully controlled: the HOST owns the values (the same
        // shape as VaultRequestSheetHost's remember(form.id) map). Pass a test
        // -side map so typed input actually lands; the default no-op
        // onValueChange would reject every keystroke by design.
        val values = mutableStateMapOf<String, String>()
        setContent {
            MaterialTheme {
                VaultRequestSheetContent(
                    form = form(),
                    stage = VaultSheetStage.Form(),
                    values = values,
                    onValueChange = { key, v -> values[key] = v },
                )
            }
        }
        onAllNodes(hasSetTextAction())[0].performTextInput("alice")
        onAllNodes(hasSetTextAction())[1].performTextInput("s3cret-42")
        // The values are in composable state — and only there.
        assertTrue(typedEditableTexts().any { "s3cret-42" in it })
        assertEquals("s3cret-42", values["password"])

        // Simulated activity recreation: a fresh composition AND a fresh host
        // map — exactly what a recreated activity produces, because the host
        // holds the values in plain `remember`, never `rememberSaveable`. The
        // sheet must render the host's (empty) values and hold no internal
        // copy of its own.
        val recreated = mutableStateMapOf<String, String>()
        setContent {
            MaterialTheme {
                VaultRequestSheetContent(
                    form = form(),
                    stage = VaultSheetStage.Form(),
                    values = recreated,
                    onValueChange = { key, v -> recreated[key] = v },
                )
            }
        }
        val restored = typedEditableTexts()
        assertTrue(restored.isNotEmpty())
        assertTrue(restored.all { it.isEmpty() }, "values leaked into saved state: $restored")
        assertTrue(recreated.isEmpty(), "recreated host state must start empty")
    }
}
