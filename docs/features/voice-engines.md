# Voice engines (Piper TTS + Vosk/neural STT)

**Last verified:** 2026-11-28

Pots speaks and listens out of the box, offline, on Android. Two bundled
engines make that possible: **Piper** synthesizes the voice (a Russian voice,
Ирина), and **Vosk** recognizes speech. A third recognition engine — the
sherpa-onnx **neural** model (GigaAM v3), downloaded on first use — is the most
accurate of the three on Russian. The system engines remain available as
alternatives in the settings.

## Speech output (TTS)

Settings → General → Озвучивание offers three engines:

- **Системный** — the platform TTS through `nl.marc-apps:tts`, as before.
- **Piper (офлайн)** — the bundled offline voice, the default for a fully
  offline phone.
- **Облачный** — a placeholder for a future cloud voice.

### The bundled voice: Ирина

The Piper voice `ru_RU-irina-medium` (a medium-quality Russian VITS voice,
22 050 Hz, trained on RHVoice material) ships inside the APK. On first use
the voice files are copied once from the assets into the app's files
directory, where the runtime loads them by path. The model then loads lazily
on the first utterance and stays in memory; every consumer — the chat speech
output, the Settings test button, the hands-free loop and the cube dialogue —
shares the same instance, so the model is never loaded twice. Switching the
engine in the settings releases the model; the next utterance re-initializes
it.

Text is phonemized with espeak-ng (the `piper-phonemize` library) and
synthesized by the sherpa-onnx runtime, streaming audio as it is produced, so
stop and barge-in cut the speech mid-sentence. The speaking rate follows the
rate slider in the settings; pitch does not apply to Piper. The streaming
callback is an explicit class (`StreamingCallback`), never a lambda: the
sherpa-onnx JNI finds it with `GetMethodID(cls, "invoke",
"([F)Ljava/lang/Integer;")` on the callback's own class, and R8 strips that
specialized method from lambdas, which aborts the process. A keep rule in
`composeApp/proguard-rules.pro` protects it in release builds.

The Settings card shows the voice (Ирина, русский), notes that it is bundled
and works without internet, and offers a test button that speaks a short
phrase through exactly the same engine the chat uses.

### Scope and fallback

Piper is Android-only in this phase. Everywhere else, and whenever the bundled
voice cannot be loaded, the app silently falls back to the system voice — a
Piper setting never leaves the app mute. When Piper is the chosen engine, the
cube dialogue (`audio_down` packets for the companion device) is synthesized
with Piper as well, falling back to the system engine if Piper fails.

## Speech recognition (STT)

Settings → General → Распознавание речи (directly under Озвучивание) picks
the engine and, for the two offline engines, the model:

- **Vosk (офлайн)** — the default. The small Russian model ships inside the
  APK and is unpacked on first use, so recognition works with no network.
- **Нейро (GigaAM v3)** — the sherpa-onnx engine with a Russian GigaAM v3 CTC
  model: the most accurate of the three on Russian, and it adds no native code,
  because the runtime is already in the APK for Piper. The model is **not** in
  the APK — it is downloaded on first use and verified before it is used.
- **Системное** — the device's own recognition service.

Three Vosk models are selectable:

| Model | Language | Source | Size |
|---|---|---|---|
| `vosk-model-small-ru-0.22` | Russian | bundled in the APK (default) | ~45 MB |
| `vosk-model-small-en-us-0.15` | English | downloaded on first use | ~40 MB |
| `vosk-model-ru-0.42` | Russian, high accuracy | downloaded on first use | ~1.8 GB |

Downloads resume per file, verify the zip while unpacking and install into the
app's files directory. The hands-free loop and every other recognizer consumer
pick up the chosen engine and model the next time a session starts.

### The neural model

Two GigaAM v3 CTC exports are selectable. Both are Russian-only, ~214 MB
installed, and live in `filesDir/stt/<model-id>/`:

| Model | Punctuation | Source (pinned revision) |
|---|---|---|
| `gigaam-v3-ctc-punct-int8` (default) | yes, with capitalisation | `csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16` |
| `gigaam-v3-ctc-int8` | no | `csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16` |

Each model is two files (`model.int8.onnx`, `tokens.txt`) fetched from the
Hugging Face mirror of the upstream sherpa-onnx release at a pinned commit, with
the size and SHA-256 pinned in the code. The card shows the size **before** the
download, a progress bar with `Отменить` while it runs, and `Удалить` once the
model is installed. A dropped connection resumes with an HTTP `Range` request
from the byte it stopped at; a checksum mismatch deletes the file and starts it
over, so a half-downloaded model can never be marked installed.

`Проверить на микрофоне` records three seconds and transcribes them with the
selected engine, through the same routing and the same decode path the dialogue
uses — the button answers «will this work when I speak to Потсик», not «does
some engine work».

### Which engine actually runs

One function decides for every consumer — the hands-free chat, the overlay pill
and dictation:

| Situation | Engine |
|---|---|
| Vosk or System selected | as picked |
| Neural selected, model installed, ≥ 700 MB free RAM | Neural |
| Neural selected, model not downloaded | **Vosk**, and the card says «Модель не скачана — пока распознаёт Vosk» |
| Neural selected, model installed, < 700 MB free RAM | **Vosk**, and the card says «Мало свободной памяти — пока распознаёт Vosk» |

Vosk is the fallback because its model ships with the app; the system
recognizer may need the network, so it is never a silent substitute. The
fallback is not silent either: the settings card renders the reason before the
user taps the microphone.

The neural engine is an **offline** recognizer — it transcribes a phrase that
has already ended, so there are no word-by-word partials. The session keeps
Vosk's end-of-speech rules (3 s of silence, 120 s cap), keeps listening after a
pause, and appends each decoded phrase to the transcript.

## Size and licensing

Bundling both engines makes the debug APK about 234 MB (voice ~63 MB, Vosk
zip ~45 MB, native runtime libraries for four ABIs ~120 MB). The neural model
adds nothing to the APK: its runtime is the same sherpa-onnx AAR Piper already
links, and the model itself is a download.

| Component | Version | License |
|---|---|---|
| sherpa-onnx runtime (vendored AAR, `composeApp/libs/`) | 2.0.1 | Apache 2.0 |
| piper-phonemize (JitPack `com.github.csukuangfj.piper-phonemize`) | v1.5.9 | **GPL v3.0** |
| Piper voice `ru_RU-irina-medium` | — | MIT (Piper voice; dataset RHVoice-derived) |
| vosk-android | 0.3.47 | Apache 2.0 |
| Vosk models | 0.22 / 0.15 / 0.42 | Apache 2.0 |
| GigaAM v3 CTC models (downloaded on first use, not in the APK) | 2025-12-16 | MIT |

Note the GPL: `piper-phonemize` is GPLv3, and Pots links it on Android. That
is compatible with Pots because the app itself is open source and the combined
work stays under compatible terms, but keep this in mind for any proprietary
redistribution of the Android build.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../ui/tts/PotsTextToSpeech.kt` | Engine-independent speaking interface + engine resolution |
| `composeApp/src/commonMain/.../ui/tts/SpeechChunks.kt` | Long-answer chunking shared by both engines |
| `composeApp/src/androidMain/.../speech/piper/PiperTextToSpeech.kt` | Piper engine: sherpa-onnx + phonemizer + audio streaming (`StreamingCallback` must stay an explicit class for JNI) |
| `composeApp/src/androidMain/.../speech/piper/PiperVoices.kt` | Asset install and location of bundled Piper voices |
| `composeApp/proguard-rules.pro` | Keep rules for sherpa-onnx, piper-phonemize and the streaming callback |
| `composeApp/src/commonMain/.../ui/settings/PiperVoiceTest.kt` | The Settings "test the voice" button backend |
| `composeApp/src/commonMain/.../ui/settings/GeneralSettings.kt` | Engine cards, voice row and test button UI |
| `composeApp/src/androidMain/.../speech/vosk/VoskModels.kt` | Vosk model registry, asset install and download |
| `composeApp/src/androidMain/.../speech/sherpa/SherpaSttModels.kt` | Neural model registry (pinned URL/size/SHA-256), resumable download, verified install, recognizer cache |
| `composeApp/src/commonMain/.../handsfree/SpeechEngineRouting.kt` | The single engine decision (model present? enough memory?) shared by every consumer |
| `composeApp/src/androidMain/.../handsfree/SherpaSpeechToText.kt` | Neural recognizer (audio capture, phrase buffering, offline decode) |
| `composeApp/src/androidMain/.../handsfree/AudioPcm.kt` | Microphone level and PCM→float conversion shared by the offline engines |
| `composeApp/src/commonMain/.../ui/settings/SttMicTest.kt` | The Settings «проверить на микрофоне» button backend |
| `composeApp/src/androidMain/.../handsfree/VoskSpeechToText.kt` | Vosk-backed recognizer (audio capture + endpointing) |
| `composeApp/src/androidMain/.../companion/cophone/CoPhoneTts.kt` | Cube dialogue TTS, Piper-first with system fallback |
| `composeApp/libs/sherpa-onnx-2.0.1.aar` | Vendored sherpa-onnx Android runtime |
| `androidApp/src/main/assets/piper/ru_RU-irina-medium/` | Bundled voice (onnx, tokens, config) |
| `androidApp/src/main/assets/vosk/vosk-model-small-ru-0.22.zip` | Bundled default Vosk model |
