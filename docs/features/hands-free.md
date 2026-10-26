# Hands-Free Voice Mode

**Last verified:** 2026-10-25

Hands-Free turns the chat into a spoken dialogue: the app listens, sends what it heard, waits for the agent, speaks the answer and listens again. The user never has to touch the screen for the whole round trip.

## Concepts

### Voice mode

A toggle in the top bar, next to the speech-output button. It is only shown on platforms that have a speech recognizer — currently Android. On every other platform the button is hidden and nothing else changes.

### The loop

The loop is a small state machine with four states:

| State | What happens |
|---|---|
| Idle | The mode is off. |
| Listening | The microphone is open; the running transcript is shown under the input field. |
| Thinking | The transcript was sent; the agent is working on it. |
| Speaking | The agent's reply is being spoken. |

The cycle is: listen → send → wait → speak → listen. The transition back to *Listening* happens when the speech has finished, so the microphone is open again the moment the agent stops talking. Long replies are split into platform-sized chunks (under 3,000 characters) and spoken in order; this avoids the Android TTS input limit that previously cut long answers short and dropped the loop back to listening mid-reply.

### Barge-in

The agent can be interrupted while it is speaking. Tapping the voice button in the *Speaking* state cuts the reply off at the word and reopens the microphone; the user can then answer instead of waiting for the agent to finish. In every other state the button starts or stops the mode.

Recognizer and synthesizer never run at the same time. That is deliberate: with the microphone open while the agent talks, the recognizer transcribes the agent's own voice, and the agent then interrupts itself. Stopping the speech before opening the microphone is what makes the interruption predictable.

## Behaviour

- The transcript is sent as an ordinary chat message, so it lands in the conversation history like a typed one and can be re-read, regenerated or answered afterwards
- While the mode is running, the regular "speak every new reply" behaviour is suspended — the loop speaks the reply itself, and letting both run would talk over each other
- Starting hands-free turns speech output on automatically, because a hands-free dialogue that silently answers is not hands-free
- The speech-output toggle keeps working in both modes. Turning it off while the loop is listening means the agent still answers and the microphone still reopens, just without reading the reply out; turning it off while the agent is speaking cuts the sentence and reopens the microphone. Turning it back on lets the next reply be spoken. The top-bar volume icon always shows the real state, whichever mode set it
- The microphone permission is requested when the user switches the mode on, not at first launch
- A recognition session that ends without a result ("no match") is not an error: the loop simply waits for the next attempt
- A real failure (no recognition service, denied permission) pauses the loop in the *Error* state; tapping the button resumes it
- Every recognition session carries an id, so a late callback from a session that was already replaced — because the user interrupted, or because a new session started — is ignored and cannot derail the loop
- Leaving the chat screen cancels the recognition session and releases the recognizer

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../handsfree/HandsFreeController.kt` | The listen → send → speak state machine, barge-in handling and session bookkeeping |
| `composeApp/src/commonMain/.../handsfree/SpeechToText.kt` | Platform-independent speech recognition interface and the platform factory |
| `composeApp/src/androidMain/.../handsfree/AndroidSpeechToText.kt` | Android implementation on top of `SpeechRecognizer` |
| `composeApp/src/androidMain/.../handsfree/SpeechToText.android.kt` | Android factory |
| `composeApp/src/desktopMain/.../handsfree/SpeechToText.desktopMain.kt` | No recognizer on desktop — the button is hidden |
| `composeApp/src/iosMain/.../handsfree/SpeechToText.iosMain.kt` | No recognizer on iOS — the button is hidden |
| `composeApp/src/wasmJsMain/.../handsfree/SpeechToText.wasmJsMain.kt` | No recognizer on the web — the button is hidden |
| `composeApp/src/commonMain/.../ui/chat/ChatScreen.kt` | Creates the controller, feeds assistant replies into it, wires the top-bar button |
| `composeApp/src/commonMain/.../ui/chat/composables/TopBar.kt` | The voice-mode button with its state icon and listening pulse |
| `composeApp/src/commonMain/.../tools/PermissionController.kt` | The `RECORD_AUDIO` runtime permission |
| `androidApp/src/main/AndroidManifest.xml` | `RECORD_AUDIO` permission declaration |
