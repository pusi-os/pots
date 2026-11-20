package com.pusios.pots.overlay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pusios.pots.LocalAppLanguageCode
import com.pusios.pots.data.AppLanguage
import com.pusios.pots.data.DataRepository
import com.pusios.pots.data.ThemeMode
import com.pusios.pots.ui.DarkColorScheme
import com.pusios.pots.ui.LightColorScheme
import com.pusios.pots.ui.dynamicui.toSpeakableText
import com.pusios.pots.ui.potsik.PotsikCloudAsker
import com.pusios.pots.ui.potsik.PotsikCloudDraft
import com.pusios.pots.ui.potsik.PotsikCloudInputPanel
import com.pusios.pots.ui.potsik.PotsikCloudPhase
import com.pusios.pots.ui.potsik.PotsikCloudState
import com.pusios.pots.ui.potsik.PotsikCloudTrouble
import com.pusios.pots.ui.potsik.PotsikCloudVoice
import com.pusios.pots.ui.potsik.PotsikShotHolder
import com.pusios.pots.ui.potsik.PotsikShotUi
import com.pusios.pots.ui.potsik.potsikScreenshotSizeLabel
import com.pusios.pots.ui.tts.DEFAULT_PIPER_VOICE_ID
import com.pusios.pots.ui.tts.PotsTextToSpeech
import com.pusios.pots.ui.tts.createPiperTextToSpeech
import com.pusios.pots.ui.tts.sayChunked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import java.util.Locale

/**
 * The text field of the cloud, as its own translucent activity.
 *
 * An overlay window is always below the keyboard — `TYPE_APPLICATION_OVERLAY`
 * cannot be above the IME — so a field drawn inside the capsule would be
 * covered the moment the keyboard came up. As an activity the field sits at the
 * bottom of the window, `adjustResize` lifts it above the keyboard, back closes
 * it, and the insets are the system's own business instead of a guess.
 *
 * The draft lives in [PotsikCloudDraft], not in this activity: closing with
 * back, or the process being killed while the user is elsewhere, must not throw
 * away what was typed.
 */
class PotsikTextInputActivity : ComponentActivity() {

    /** The screen's own scope: it ends with the screen, and nothing it started outlives it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository: DataRepository = get()
        val appSettings = get<com.pusios.pots.data.AppSettings>()
        val asker = PotsikCloudAsker(scope, repository)

        // The synthesizer is only for [Озвучить] here; the voice loop of the
        // overlay owns the microphone and this screen never opens one.
        val textToSpeech: PotsTextToSpeech? = runCatching {
            createPiperTextToSpeech(
                appSettings.getTtsPiperVoice().ifEmpty { DEFAULT_PIPER_VOICE_ID },
            )
        }.getOrNull()

        setContent {
            // The pill's state and the draft are read here, inside the
            // composition: a snapshot read outside it would never redraw.
            var state by remember { mutableStateOf(PotsikCloudState()) }
            val draft by PotsikCloudDraft.text.collectAsState()
            val pendingShot by PotsikShotHolder.shot.collectAsState()
            // Decoded once per frame, not once per recomposition.
            val shotUi = remember(pendingShot) {
                pendingShot?.let { shot ->
                    PotsikShotUi(preview = shot.preview(), label = potsikScreenshotSizeLabel(shot.width, shot.height))
                }
            }
            val themeMode by appSettings.themeModeFlow.collectAsState()
            val appLanguage by appSettings.appLanguageFlow.collectAsState()
            val systemInDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                ThemeMode.System -> systemInDark
                ThemeMode.Light -> false
                ThemeMode.Dark, ThemeMode.OledBlack -> true
            }
            val languageCode = when (appLanguage) {
                AppLanguage.System -> Locale.getDefault().language
                AppLanguage.Russian -> "ru"
                AppLanguage.English -> "en"
            }
            val uiScope = rememberCoroutineScope()
            LaunchedEffect(asker) {
                asker.answer.collect { answer ->
                    when (answer) {
                        is PotsikCloudAsker.Answer.Thinking -> state = PotsikCloudState(
                            phase = PotsikCloudPhase.Thinking,
                            transcript = answer.question,
                        )

                        is PotsikCloudAsker.Answer.Answered -> {
                            // The field is cleared only once the question has
                            // actually gone: a failed send keeps the text.
                            PotsikCloudDraft.clear()
                            PotsikShotHolder.clear()
                            state = PotsikCloudState(phase = PotsikCloudPhase.Speaking, reply = answer.text)
                        }

                        is PotsikCloudAsker.Answer.Failed -> state = PotsikCloudState(
                            phase = PotsikCloudPhase.Error,
                            transcript = answer.question,
                            trouble = PotsikCloudTrouble.NoNetwork,
                            unsent = true,
                        )

                        PotsikCloudAsker.Answer.Idle -> Unit
                    }
                }
            }
            CompositionLocalProvider(LocalAppLanguageCode provides languageCode) {
                MaterialTheme(colorScheme = if (dark) DarkColorScheme else LightColorScheme) {
                    PotsikCloudInputPanel(
                        state = state,
                        draft = draft,
                        shot = shotUi,
                        onDraftChange = PotsikCloudDraft::set,
                        onSend = {
                            val pending = PotsikShotHolder.shot.value
                            val file = pending?.let { potsikShotFile(this@PotsikTextInputActivity, it) }
                            if (pending != null && file == null) {
                                // The frame could not be handed to the chat:
                                // nothing is sent, so nothing is lost — the
                                // draft and the picture stay where they are.
                                state = PotsikCloudState(
                                    phase = PotsikCloudPhase.Error,
                                    trouble = PotsikCloudTrouble.ScreenCaptureFailed,
                                )
                            } else {
                                asker.ask(draft, listOfNotNull(file))
                            }
                        },
                        onRemoveShot = {
                            PotsikShotHolder.clear()
                            state = PotsikCloudState()
                        },
                        onRetry = {
                            if (state.unsent) {
                                asker.retry()
                            } else {
                                state = PotsikCloudState()
                            }
                        },
                        onSpeak = textToSpeech?.let { tts ->
                            {
                                // One owner for the microphone: while the
                                // overlay's voice loop is running, this says so
                                // instead of speaking over it.
                                if (PotsikCloudVoice.active.value) {
                                    state = PotsikCloudState(
                                        phase = PotsikCloudPhase.Error,
                                        trouble = PotsikCloudTrouble.MicBusy,
                                    )
                                } else {
                                    val reply = state.reply
                                    uiScope.launch {
                                        runCatching { tts.sayChunked(reply.toSpeakableText(), true, true) }
                                    }
                                }
                            }
                        },
                        onOpenInChat = {
                            // The app's own launch intent: this module cannot
                            // reference the activity class of the application
                            // module it is packaged into.
                            packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                startActivity(intent)
                            }
                            finish()
                        },
                        onClose = { finish() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // The screen is a panel over whatever the user was doing: everything
        // around it is transparent, and a tap outside the panel closes it.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        // A floating translucent window wraps its content by default; the
        // panel wants the whole screen so that the field can sit at the bottom
        // and `adjustResize` can lift it above the keyboard.
        window.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onDestroy() {
        super.onDestroy()
        // The question is not lost when the screen closes: the draft is kept
        // and the chat has the turn if it was already sent.
        scope.cancel()
    }
}
