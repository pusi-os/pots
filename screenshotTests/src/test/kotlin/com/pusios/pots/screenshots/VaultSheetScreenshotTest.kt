// Vault credential request sheet screenshots (card 2/5, §6.1): the five sheet
// stages — empty form, filled form, locked, wrong master password, saved. The
// sheet body composes without a bridge or a Koin container: stage + form in,
// callbacks ignored.

@file:OptIn(ExperimentalResourceApi::class)

package com.pusios.pots.screenshots

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.pusios.pots.data.vault.VaultFieldInput
import com.pusios.pots.data.vault.VaultFormFieldSpec
import com.pusios.pots.data.vault.VaultFormRequest
import com.pusios.pots.ui.DarkColorScheme
import com.pusios.pots.ui.LightColorScheme
import com.pusios.pots.ui.Theme
import com.pusios.pots.ui.chat.VaultRequestSheetContent
import com.pusios.pots.ui.chat.VaultSheetStage
import com.pusios.pots.ui.dynamicui.LocalPreviewImages
import com.pusios.pots.ui.vault.VaultUnlockError
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class VaultSheetScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_9A.copy(softButtons = false),
        showSystemUi = true,
        maxPercentDifference = 0.1,
    )

    private val previewImages = mutableMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()

    @Before
    fun setup() {
        setResourceReaderAndroidContext(paparazzi.context)
    }

    private fun snap(
        colorScheme: ColorScheme,
        content: @Composable () -> Unit,
    ) {
        val theme = if (colorScheme == DarkColorScheme) {
            "android:Theme.Material.NoActionBar"
        } else {
            "android:Theme.Material.Light.NoActionBar"
        }
        paparazzi.unsafeUpdateConfig(theme = theme)
        paparazzi.snapshot {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalPreviewImages provides previewImages,
            ) {
                Theme(colorScheme = colorScheme) {
                    content()
                }
            }
        }
    }

    private val form = VaultFormRequest(
        id = "form-1",
        mode = "create",
        name = "GitHub",
        reason = "I need the token to set up CI in your repository",
        fields = listOf(
            VaultFormFieldSpec(key = "username", label = "Username", input = VaultFieldInput.TEXT),
            VaultFormFieldSpec(
                key = "password",
                label = "Password",
                input = VaultFieldInput.SECRET,
                required = true,
            ),
            VaultFormFieldSpec(key = "note", label = "Note", input = VaultFieldInput.MULTILINE),
        ),
    )

    @Test
    fun vaultRequestSheet_empty_dark() {
        snap(DarkColorScheme) {
            VaultRequestSheetContent(form = form, stage = VaultSheetStage.Form())
        }
    }

    @Test
    fun vaultRequestSheet_filled_dark() {
        snap(DarkColorScheme) {
            VaultRequestSheetContent(
                form = form,
                stage = VaultSheetStage.Form(),
                initialValues = mapOf("username" to "alice", "password" to "s3cret-42"),
            )
        }
    }

    @Test
    fun vaultRequestSheet_unlock_dark() {
        snap(DarkColorScheme) {
            VaultRequestSheetContent(form = form, stage = VaultSheetStage.Unlock())
        }
    }

    @Test
    fun vaultRequestSheet_unlockWrongPassword_dark() {
        snap(DarkColorScheme) {
            VaultRequestSheetContent(
                form = form,
                stage = VaultSheetStage.Unlock(error = VaultUnlockError.WRONG_PASSWORD),
            )
        }
    }

    @Test
    fun vaultRequestSheet_saved_dark() {
        snap(DarkColorScheme) {
            VaultRequestSheetContent(
                form = form,
                stage = VaultSheetStage.Saved(itemName = "GitHub"),
            )
        }
    }

    @Test
    fun vaultRequestSheet_empty_light() {
        snap(LightColorScheme) {
            VaultRequestSheetContent(form = form, stage = VaultSheetStage.Form())
        }
    }
}
