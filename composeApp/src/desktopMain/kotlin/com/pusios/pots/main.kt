@file:Suppress("ktlint:standard:filename")
@file:OptIn(ExperimentalDesktopTarget::class)

package com.pusios.pots

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import nl.marc_apps.tts.TextToSpeechEngine
import nl.marc_apps.tts.TextToSpeechInstance
import nl.marc_apps.tts.experimental.ExperimentalDesktopTarget
import nl.marc_apps.tts.rememberTextToSpeechOrNull
import org.jetbrains.compose.resources.painterResource
import pots.composeapp.generated.resources.Res
import pots.composeapp.generated.resources.logo

fun main() {
    System.setProperty("apple.awt.application.appearance", "system")
    // Help AWT/Skiko pick up HiDPI on Linux/Wayland (Sway, GNOME fractional scaling).
    // Without this, the JVM ignores GDK_SCALE and renders at 1× on a hi-res monitor.
    if (System.getProperty("sun.java2d.uiScale.enabled") == null) {
        System.setProperty("sun.java2d.uiScale.enabled", "true")
    }
    if (System.getProperty("sun.java2d.uiScale") == null) {
        System.setProperty("sun.java2d.uiScale", "auto")
    }
    application {
        // The window only sets the initial size: everything inside the content
        // follows the live window size, so resizing stretches/shrinks the chat,
        // the settings columns and the character canvas with it. A minimum size
        // keeps the toolbars and pickers usable when the window gets small.
        val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Pots",
            icon = painterResource(Res.drawable.logo),
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(640, 480)
            }
            // Defer TTS initialization until after the first frame
            var ttsReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { ttsReady = true }
            val textToSpeech: TextToSpeechInstance? = if (ttsReady) {
                rememberTextToSpeechOrNull(TextToSpeechEngine.Google)
            } else {
                null
            }

            val navController = rememberNavController()
            App(
                navController = navController,
                textToSpeech = textToSpeech,
            )
        }
    }
}
