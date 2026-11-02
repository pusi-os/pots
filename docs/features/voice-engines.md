# Voice engines (Piper TTS + Vosk STT)

**Last verified:** 2026-11-03

Pots speaks and listens out of the box, offline, on Android. Two bundled
engines make that possible: **Piper** synthesizes the voice (a Russian voice,
Ирина), and **Vosk** recognizes speech. The system engines remain available
as alternatives in the settings.

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
the engine and the Vosk model:

- **Vosk (офлайн)** — the default. The small Russian model ships inside the
  APK and is unpacked on first use, so recognition works with no network.
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

## Size and licensing

Bundling both engines makes the debug APK about 234 MB (voice ~63 MB, Vosk
zip ~45 MB, native runtime libraries for four ABIs ~120 MB).

| Component | Version | License |
|---|---|---|
| sherpa-onnx runtime (vendored AAR, `composeApp/libs/`) | 2.0.1 | Apache 2.0 |
| piper-phonemize (JitPack `com.github.csukuangfj.piper-phonemize`) | v1.5.9 | **GPL v3.0** |
| Piper voice `ru_RU-irina-medium` | — | MIT (Piper voice; dataset RHVoice-derived) |
| vosk-android | 0.3.47 | Apache 2.0 |
| Vosk models | 0.22 / 0.15 / 0.42 | Apache 2.0 |

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
| `composeApp/src/androidMain/.../handsfree/VoskSpeechToText.kt` | Vosk-backed recognizer (audio capture + endpointing) |
| `composeApp/src/androidMain/.../companion/cophone/CoPhoneTts.kt` | Cube dialogue TTS, Piper-first with system fallback |
| `composeApp/libs/sherpa-onnx-2.0.1.aar` | Vendored sherpa-onnx Android runtime |
| `androidApp/src/main/assets/piper/ru_RU-irina-medium/` | Bundled voice (onnx, tokens, config) |
| `androidApp/src/main/assets/vosk/vosk-model-small-ru-0.22.zip` | Bundled default Vosk model |
