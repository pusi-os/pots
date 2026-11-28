# Hands-Free Voice Mode

**Last verified:** 2026-11-28

Hands-Free turns the chat into a spoken dialogue: the app listens, sends what it heard, waits for the agent, speaks the answer and listens again. The user never has to touch the screen for the whole round trip.

## Concepts

### Voice mode

On a supported platform, the empty chat offers an animated circular voice button. Once a conversation has begun, a toggle also appears in the top bar next to speech output; it remains visible while voice mode is active even before the first message. Voice controls are only shown on platforms that have a speech recognizer — currently Android.

### Recognition engines

Settings → General → Speech recognition picks the engine:

- **Vosk (offline)** — the default. The Russian small model ships inside the app, so recognition works with no network at all. Vosk streams the microphone itself and reports the end of an utterance through its own endpointer. More models (English small, a large Russian one) can be selected and are installed on first use.
- **System** — the device's own recognition service. Depending on the device it may need the network or a downloaded language pack.

The chosen engine and model are picked up by the loop the next time the chat opens the recognizer.

### Limits of the system recognizer

The system engine is not ours: its silence thresholds, whether it needs the network, and whether it delivers partial results at all are implementation-defined and differ per device and per vendor ROM. What the user can see:

- The session may end on its own after a pause that is shorter or longer than the 3.0 s the loop uses. The text recognised so far is still handed over, and the composer keeps it either way.
- `onRmsChanged` is the only level signal. On some devices it is coarse, so the wave is less lively than with Vosk.
- A session that stops answering is failed by the loop's inactivity watchdog after 15 s and reported as «Распознавание не отвечает».
- `ERROR_SPEECH_TIMEOUT` means the user said nothing in time: it is reported as «Ничего не расслышал», not as a failure, and the microphone reopens by itself.

Vosk has none of these limits: it owns the microphone, measures the level from the PCM buffer it is already reading, and its endpoint no longer ends the session.

### The speaking voice

The loop speaks through the same engine the chat uses (Settings → General → Озвучивание): the bundled offline Piper voice when it is selected, otherwise the system voice. See `docs/features/voice-engines.md`.

### The loop

The loop is a small state machine with six states:

| State | What happens |
|---|---|
| Idle | The mode is off. |
| Listening | The microphone is open. The running transcript goes into the composer, and the recording indicator above it shows the level, the timer and the state. |
| Thinking | The transcript was sent; the agent is working on it. |
| Speaking | The agent's reply is being spoken. |
| NoMatch | Nothing was recognised. Shown for two seconds, then the microphone reopens by itself. |
| Error | The session failed (no service, no permission, recognizer stopped answering). The recognised text stays in the composer and the indicator offers «Повторить». |

The cycle is: listen → send → wait → speak → listen. The transition back to *Listening* happens when the speech has finished, so the microphone is open again the moment the agent stops talking. Long replies are split into platform-sized chunks (under 3,000 characters) and spoken in order; this avoids the Android TTS input limit that previously cut long answers short and dropped the loop back to listening mid-reply.

### The recording indicator

A strip sits directly above the composer (in `ChatModeScreen`, above `QuestionInput`) and is composed only while a session is running — *Listening*, *Speech detected*, *Finalising*, *Sending*, or an *Error* the user has not dismissed yet. It exists because the mode must never be silent about what it is doing, and must never drop text it has already recognised.

**What it contains**

| Part | Specification |
|---|---|
| Sound wave | 16 bars, 3 dp wide, 2 dp apart, 2–28 dp high, fully rounded caps. Heights come from the real microphone level, not from a canned animation: `SpeechToText` publishes RMS (Vosk, computed from the PCM read loop) or `onRmsChanged` dB (system engine). One bar ≈ 64 ms of audio, kept in a 16-slot ring buffer. Attack ≈ 90 ms, release ≈ 260 ms (25 % per frame), so the wave follows the voice and does not strobe on silence. |
| Timer | `m:ss` (`0:07`, `1:23`), `FontFamily.Monospace`, 20 sp, `PotsRecordingColors.led` — the LED-clock look without a shadow: a glow needs its own draw layer per digit and reads worse on an OLED black background. |
| State line | One short word — see the state table below. In *Error* the reason follows on a second line, `bodySmall`, `colorScheme.error`. |
| Live text | The running transcript, one line, `bodySmall`, `onSurfaceVariant`, ellipsis. The full text lives in the composer: the transcript is written into the field as it is recognised, replacing only the stretch dictation itself wrote, so text typed during the session survives and the field and the strip never disagree. |
| Actions | Three icon-only buttons, 48×48 dp targets, 8 dp apart, vector icons only (no emoji). |

**States and what the user sees**

| State | Strip | Wave | Timer | Actions |
|---|---|---|---|---|
| Listening | «Слушаю…» | flat, `waveIdle` | runs | restart · stop-and-keep · send |
| Speech detected | «Записываю…» | live, `wave` | runs | restart · stop-and-keep · send |
| Finalising | «Распознаю…» | flat, `waveIdle` | frozen | — (buttons disabled) |
| Sending | «Отправляю…» | hidden | frozen | — |
| Error | «Ошибка записи» + reason | hidden | frozen | retry · stop-and-keep · restart |

The strip has no *idle* state of its own: when the session ends the strip leaves the composition and the composer returns to normal, with the recognised text already in it.

**Actions** (icon only; the label below is the `contentDescription`)

| Icon | `contentDescription` (ru) | Effect |
|---|---|---|
| `ic_refresh`, tinted `colorScheme.error` | «Отменить и начать заново» | Drops the text dictated in this session, restores the field without it (the draft typed before or during the session stays), and opens a new session at once. |
| `ic_mic_done` (new vector: mic + check) | «Остановить и вставить текст в поле» | Ends the session, keeps the text in the field, sends nothing. |
| `ic_send`, tinted `colorScheme.primary` | «Отправить сейчас» | Ends the session and submits the field text immediately. Disabled while the field is empty — an enabled button that does nothing is worse than a visibly disabled one. |
| `ic_refresh`, tinted `colorScheme.primary` (Error only) | «Повторить» | Opens a new session; the text stays in the field. |

In *Error* the strip offers three actions, not the four of a live session: retry, stop-and-keep (so the text can be left in the composer and the mode switched off) and restart. «Отправить сейчас» is not there because the composer's own send button is already one tap away and the text is not going anywhere.

**Timing — the rules that make long dictation work**

- **No phrase-length limit.** The inactivity watchdog introduced for the dead-service case stays, but it measures *inactivity*, not phrase length: it fires only when no callback and no above-threshold audio have arrived for `LISTEN_TIMEOUT_MS` (15 s), and every partial result, every `onBeginningOfSpeech` and every audio frame above the speech threshold re-arms it. A session in which the user is talking is never cancelled by a timer.
- **The engine's own endpoint is not the end of the session.** Vosk ends an utterance after a short trailing pause and used to close the microphone there, so the rest of a long phrase was never recorded. Its endpoint now only means "this segment is final": the segment's text is appended to the running transcript and the same session keeps listening. The system recognizer behaves the same way from the loop's point of view — it may end its own session, but nothing in the loop ends it while speech is being reported.
- **End of speech is silence, not a clock.** 3.0 s of audio below the speech threshold (level < 0.25 on the engines' 0..1 scale) finalises the utterance. 3.0 s is chosen because the bundled Vosk model is configured with trailing-silence rules of 0.5 / 1.0 / 2.0 s, so anything at or below 2 s would split a sentence at its first comma; 5 s is where a user assumes the microphone died. 3.0 s survives a normal intra-sentence pause and still feels immediate.
- **Absolute cap.** 120 s of continuous speech finalise and send the utterance, so the microphone is not held forever. Vosk itself restarts its decoder after 20,000 frames (≈ 600 s), so 120 s is well inside the engine's own limit.
- **Nothing is dropped.** Every exit path keeps what was recognised: silence, the cap and the stop button leave the text in the field, an error leaves the text in the field, and only the explicit «Отменить и начать заново» discards it — that is the user's stated intent.
- **Initial silence.** 8 s without a single above-threshold frame ends the session as *NoMatch* («Ничего не расслышал»), the two-second notice is shown and the microphone reopens.

**Colours — tokens, never raw hex in the component**

| Token (`potsRecordingColors()`) | Dark | Light | Used for |
|---|---|---|---|
| `wave` | `#00FF66` | `#00802B` | live wave bars |
| `waveIdle` | `wave` at 30 % | `wave` at 30 % | flat wave, Finalising |
| `led` | `#7CFFB2` | `#00663A` | timer digits |
| `surface` | `surfaceVariant` at 55 % | `surfaceVariant` at 70 % | plate fill (glass) |
| `border` | `primary` at 35 % | `primary` at 35 % | 1 dp plate border |

The tokens live in `ui/Theme.kt` rather than in the component so the overlay in 3.21.5 draws the same neon instead of copying hex values.

Contrast (WCAG 2.1, measured): wave on `surface` 12.3 : 1 dark / 4.6 : 1 light, LED digits 13.3 : 1 dark / 6.3 : 1 light — above 4.5 : 1 for text and far above 3 : 1 for non-text, so the wave and the digits stay readable without relying on colour alone (the state line is always text).

The plate is glass by fill, border and radius, not by blur: `Modifier.blur` is a no-op below Android 12, so legibility must not depend on it.

**Accessibility**

- Every icon button is a 48×48 dp target with a `contentDescription`; nothing is conveyed by colour only — the state is always a word, the error always a sentence.
- The state line is a `liveRegion = LiveRegionMode.Polite` node, so a screen reader announces «Слушаю…» → «Записываю…» → «Распознаю…» and any error without stealing focus.
- The timer carries `contentDescription = «Длительность записи 0:07»` and its announcement is quantised to 5 seconds — a per-second announcement would flood the screen reader.
- The wave is decorative for a screen reader (no semantics) because the same information is in the state line; it is not the only signal of anything.
- The bars ease with `animateFloatAsState`, which follows the platform animation scale: with animations switched off they snap to each new level instead of easing, so reduced motion still shows real data without motion.

### Barge-in

The agent can be interrupted while it is speaking. Tapping the voice button in the *Speaking* state cuts the reply off at the word and reopens the microphone; the user can then answer instead of waiting for the agent to finish. In every other state the button starts or stops the mode.

Recognizer and synthesizer never run at the same time. That is deliberate: with the microphone open while the agent talks, the recognizer transcribes the agent's own voice, and the agent then interrupts itself. Stopping the speech before opening the microphone is what makes the interruption predictable.

### In the overlay (the cloud)

The same controller drives the capsule that opens on a double tap on Потсик outside the app — see [The Cloud](potsik-cloud.md). What differs there:

- A synthesizer is optional. The loop counts as supported when a recognizer exists (`isSupported` is `speechToText != null`): a device without a voice still listens and answers, and the microphone reopens as soon as the reply is in — the answer itself is read on the screen.
- The screen state is checked before the microphone opens. An overlay cannot raise the system permission dialog, and Android does not deliver recognition while the screen is off, so the pill says «Слушаю только при включённом экране» instead of pretending to listen.
- The microphone has one owner. While the overlay's loop runs, the text field's [Озвучить] answers «Сначала остановите голосовой режим» instead of talking over it.
- The screen going off closes the capsule, and the loop stops with it (`ACTION_SCREEN_OFF`, registered by `PotsOverlayService` for the lifetime of its window).
- Barge-in is a tap on the pill in *Speaking*, in the same order as the app's voice button: the speech stops first, the microphone opens after.
- Haptics only: a `CONFIRM` tick when the capsule opens and on a tap, a `SEGMENT_TICK` when a new recognition session starts. Nothing buzzes continuously.

## Behaviour

- The transcript is sent as an ordinary chat message, so it lands in the conversation history like a typed one and can be re-read, regenerated or answered afterwards
- While the mode is running, the regular "speak every new reply" behaviour is suspended — the loop speaks the reply itself, and letting both run would talk over each other
- Starting hands-free turns speech output on automatically, because a hands-free dialogue that silently answers is not hands-free
- The speech-output toggle keeps working in both modes. Turning it off while the loop is listening means the agent still answers and the microphone still reopens, just without reading the reply out; turning it off while the agent is speaking cuts the sentence and reopens the microphone. Turning it back on lets the next reply be spoken. The top-bar volume icon always shows the real state, whichever mode set it
- The microphone permission is requested when the user switches the mode on, not at first launch
- A recognition session that ends without a result («no match») is not an error: the loop announces `HandsFreeStatus.NoMatch` for two seconds and then reopens the microphone on its own. It is a status of its own rather than *Error* on purpose — the chat pauses the loop on *Error*, and a missed phrase must not pause anything; a tap on the button during the notice skips the wait instead of stopping the mode
- A real failure (no recognition service, denied permission, a recognizer that stops answering) pauses the loop in the *Error* state; the recording indicator shows the reason and «Повторить», and the recognised text stays in the composer
- Every recognition session carries an id, so a late callback from a session that was already replaced — because the user interrupted, or because a new session started — is ignored and cannot derail the loop
- Leaving the chat screen cancels the recognition session and releases the recognizer
- On Android 10+, the system assist gesture (long-press Home or power) and the Quick Settings tile «Pots» start a fresh chat and turn this loop on. The role cannot be requested in code — Settings → General sends the user to default apps. There is no always-on «hey pots» wake word.

## UI strings

| Key | ru | en |
|---|---|---|
| `handsfree_state_listening` | Слушаю… | Listening… |
| `handsfree_state_speech` | Записываю… | Recording… |
| `handsfree_state_finalising` | Распознаю… | Finishing… |
| `handsfree_state_sending` | Отправляю… | Sending… |
| `handsfree_state_error` | Ошибка записи | Recording error |
| `handsfree_error_no_speech` | Ничего не расслышал | Didn't catch that |
| `handsfree_error_mic` | Нет доступа к микрофону | No microphone access |
| `handsfree_error_engine` | Распознавание не отвечает | Speech recognition isn't responding |
| `handsfree_error_no_service` | На этом устройстве нет распознавания речи | This device has no speech recognition |
| `handsfree_error_failed` | Не удалось распознать речь | Speech recognition failed |
| `handsfree_action_restart` | Отменить и начать заново | Cancel and start over |
| `handsfree_action_keep_text` | Остановить и вставить текст в поле | Stop and keep the text |
| `handsfree_action_send_now` | Отправить сейчас | Send now |
| `handsfree_action_retry` | Повторить | Retry |
| `handsfree_timer_content_description` | Длительность записи %1$s | Recording length %1$s |

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../handsfree/HandsFreeController.kt` | The listen → send → speak state machine, inactivity watchdog, silence-based end of utterance, barge-in handling and session bookkeeping |
| `composeApp/src/commonMain/.../handsfree/SpeechToText.kt` | Platform-independent speech recognition interface (including the microphone level) and the platform factory |
| `composeApp/src/androidMain/.../handsfree/AndroidSpeechToText.kt` | Android implementation on top of `SpeechRecognizer` |
| `composeApp/src/androidMain/.../handsfree/VoskSpeechToText.kt` | Offline Vosk implementation (bundled Russian model), own end-of-speech detection and level metering |
| `composeApp/src/androidMain/.../speech/vosk/VoskModels.kt` | Shared Vosk model install/load (assets first, download fallback) |
| `composeApp/src/androidMain/.../handsfree/SpeechToText.android.kt` | Android factory (picks the engine from settings) |
| `composeApp/src/desktopMain/.../handsfree/SpeechToText.desktopMain.kt` | No recognizer on desktop — the button is hidden |
| `composeApp/src/iosMain/.../handsfree/SpeechToText.iosMain.kt` | No recognizer on iOS — the button is hidden |
| `composeApp/src/wasmJsMain/.../handsfree/SpeechToText.wasmJsMain.kt` | No recognizer on the web — the button is hidden |
| `composeApp/src/commonMain/.../ui/chat/ChatScreen.kt` | Creates the controller, feeds assistant replies into it, wires the top-bar button and the recording strip to the composer |
| `composeApp/src/commonMain/.../ui/chat/composables/HandsFreeRecordingBar.kt` | The recording indicator: level wave, LED timer, state line and the three actions |
| `composeApp/src/commonMain/composeResources/drawable/ic_mic_done.xml` | «Stop and keep the text» icon (microphone + check), vector only |
| `composeApp/src/commonMain/composeResources/values{,-ru}/strings.xml` | The indicator's states, reasons and action labels |
| `composeApp/src/androidMain/.../overlay/PotsikCloudOverlay.kt` | The same loop in the desktop capsule's overlay window (the voice pill) |
| `composeApp/src/androidMain/.../overlay/PotsikTextInputActivity.kt` | The capsule's text field; the second owner of «Озвучить», which defers to the pill |
| `composeApp/src/commonMain/.../ui/tts/PotsTextToSpeech.kt` | Engine-independent speaking interface the loop talks to |
| `composeApp/src/commonMain/.../ui/tts/SpeechChunks.kt` | Splits long replies into platform-sized chunks |
| `composeApp/src/commonMain/.../ui/chat/composables/TopBar.kt` | The voice-mode button with its state icon and listening pulse |
| `composeApp/src/commonMain/.../ui/chat/composables/EmptyState.kt` | Animated voice-mode entry in the empty chat |
| `composeApp/src/commonMain/.../assistant/AssistantAvailability.kt` | Whether the assistant role can be held on this device |
| `composeApp/src/commonMain/.../ui/settings/AssistantSettings.kt` | Settings card that opens default-apps |
| `androidApp/.../PotsAssistTileService.kt` | Quick Settings tile that fires `ACTION_ASSIST` |
| `composeApp/src/commonMain/.../tools/PermissionController.kt` | The `RECORD_AUDIO` runtime permission |
| `androidApp/src/main/AndroidManifest.xml` | `RECORD_AUDIO`, `ACTION_ASSIST`, QS tile |
