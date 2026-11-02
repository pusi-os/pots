# Plan: mini-app completion + Piper TTS + Vosk STT (out-of-the-box offline voice)

Status: in progress.
Date: 2026-11-02

## Goal

One build where:
1. Mini-apps are a finished, polished flow: create in Interactive UI → save with its
   backend (collections/records) → pin to the home mini-app panel → run standalone.
2. Piper voice **Ирина** (Russian) is bundled in the APK and works offline for chat TTS
   on the phone and for the ESP32 companion dialogue.
3. Vosk offline recognition is bundled (Russian small model), selectable in
   Settings → General → Speech recognition (under the Speech output section), and drives
   the hands-free loop. Native/system recognition stays as an option.

Guiding principles (Karpathy): smallest possible interfaces, end-to-end working slices,
no speculative abstraction, every step independently testable, dependencies pinned and
documented.

## Research findings (2026-11-02)

### Mini-apps
- The studio already has New / Save / Library in the interactive top bar; save already
  combines draft screens + declared collections + records + files (`MiniAppRepository.saveDraft`).
- Gaps: the glass toolbar "+" chip carries a text label (user wants icon-only vector);
  the save dialog does not show what backend will be attached and does not offer pinning
  the app to the home panel; the authoring skill lacks an explicit final
  "save and link the backend" step.

### Piper TTS
- Piper voices run on **sherpa-onnx** (k2-fsa). Android path proven by upstream sample
  `SherpaOnnxTts`:
  - `sherpa-onnx-2.0.1.aar` (GitHub releases only, no Maven coordinate) — JNI + Kotlin
    API (`OfflineTts`, loads models from Android assets).
  - `com.github.csukuangfj.piper-phonemize:piper-phonemize:v1.5.9` (JitPack) — espeak-ng
    text frontend: `piperPhonemizeInitialize(ctx)` + `piperPhonemizeText(text, lang)`.
- Voice: `ru_RU-irina-medium.onnx` (63.2 MB) + `.onnx.json` from
  `https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/ru/ru_RU/irina/medium/...`
  (MIT license).
- Piper lives on Android only in this phase; desktop/iOS keep native TTS. Both target
  use cases (phone chat TTS, ESP32 companion dialogue via `CoPhoneTts`) run on Android.

### Vosk STT
- `com.alphacephei:vosk-android:0.3.47` is already a dependency; `libvosk.so` already in
  the APK for 4 ABIs; `CoPhoneAsr` (companion) already downloads/loads models.
- Recommended Russian mobile model (alphacephei.com/vosk/models):
  **`vosk-model-small-ru-0.22`** (45 MB, Apache 2.0) — the only small Russian model;
  the big `vosk-model-ru-0.42` (1.8 GB) is server-grade and stays an optional download.
- Android system `SpeechRecognizer` (current engine) usually needs network/offline packs
  per device → kept as "System" option; Vosk becomes the offline default.

### Size impact
Current release APK ≈ 45 MB. Added: sherpa-onnx native libs (~20 MB compressed),
piper-phonemize libs + espeak-ng-data, Irina voice (~60 MB), Vosk ru-small zip (~45 MB)
→ APK ≈ 160–180 MB. Acceptable for dashboard/internal distribution; noted in the report.

## Steps

### Step 1 — Mini-apps: finish the flow
1. Glass toolbar: icon-only circular "+" button (no text label) in both empty and
   populated states; keep tune chip and app icons.
2. Save dialog: show what will be saved (screens, collections, records, files — the app
   backend) + checkbox "Add to the mini-app panel" (default on for new apps).
3. `ChatViewModel.saveMiniApp`: accept the pin flag; after saving, pin via repository
   toolbar selection. Snackbar as today.
4. `MiniAppRepository`: add pin/unpin helpers on top of `toolbarIds`.
5. `SKILL.md` (mini-app-authoring): add the final step — verify/declare the backend
   (collections + records), replace callbacks with local actions, then instruct the user
   to press Save in the top toolbar and offer pinning.
6. Tests: repository pin helpers; existing save-flow tests stay green.

### Step 2 — Speech settings plumbing (commonMain)
1. `AppSettings`: `SttEngine { System, Vosk }`; keys `stt_engine`, `stt_vosk_model`;
   state flows.
2. `DataRepository` / `RemoteDataRepository` / `FakeDataRepository` wiring.
3. `SettingsUiState` / `SettingsActions` / `SettingsViewModel` wiring.

### Step 3 — Vosk recognition (Android)
1. Extract `VoskModels` manager from `CoPhoneAsr` (install from bundled asset zip →
   fallback network download, unpack with zip-slip guard, load, delete, progress).
   `CoPhoneAsr` delegates and keeps its API.
2. Bundle `vosk-model-small-ru-0.22.zip` in `androidApp` assets (commit the file).
3. `VoskSpeechToText` implements `SpeechToText`: AudioRecord 16 kHz mono → streaming
   `Recognizer` with endpointing; partial results; final on endpoint/stop; NoMatch on
   empty session; model auto-install on first start.
4. `createSpeechToText(engine: SttEngine)` expect/actual on all platforms;
   `ChatScreen` passes the stored engine; hands-free button stays visible on Android for
   both engines.
5. Settings UI: new "Speech recognition" card under the TTS card in General — engine
   picker (Vosk offline default / System), model picker (Russian small default, English
   small, Russian big with size warning), install status/progress and delete.

### Step 4 — Piper TTS (Android)
1. Vendor `sherpa-onnx-2.0.1.aar` into `composeApp/libs/`; add JitPack repo;
   `piper-phonemize:v1.5.9` dependency (androidMain).
2. Bundle `ru_RU-irina-medium.onnx` + `.onnx.json` in assets (commit the files).
3. `PiperVoiceManager` (androidMain): install voice from assets once into files dir,
   expose readiness state.
4. `PiperTtsEngine` (androidMain): own the `OfflineTts` instance + phonemizer; serial
   speak queue; AudioTrack playback; `stop()`; safe release.
5. New seam in commonMain: `PotsTextToSpeech` interface (`say`, `stop`, `close`) +
   `sayChunked` extension moved onto it. Adapters: `NativeTextToSpeech` (wraps
   nl.marc instance) and `PiperTextToSpeech`. `App`/`ChatScreen`/`TopBar`/`BotMessage`/
   `HandsFreeController` switch to the interface; platform entry points construct the
   adapter for the selected engine and react to engine changes.
6. Settings UI: Piper option becomes real — voice card "Ирина (русский)" with status
   (bundled / installing / ready), a Test button, speed control.
7. `CoPhoneTts`: use Piper when engine = Piper and the engine is ready; otherwise
   system TTS (fallback keeps the companion always speaking).

### Step 5 — Verify, document, ship
1. `spotlessApply` → `desktopTest` → Android foss debug compile → release build.
2. Docs: update `docs/features/mini-apps.md`, `docs/features/hands-free.md`; new
   `docs/features/voice-engines.md` (Piper + Vosk, models, licenses, sizes).
3. Publish with `scripts/publish-apk.sh` to dashboard 222; verify manifest.
4. Report with on-phone checklist: mini-app create/save/pin/run, Piper voice in chat and
   hands-free, Vosk recognition offline (airplane mode), companion dialogue voice.

## Out of scope (this phase)
- Piper on desktop/iOS (keep native engines there).
- Cloud TTS engine (enum placeholder stays).
- Vosk on desktop/iOS/web (no recognizer there today).
- Per-ABI APK splits (single universal APK keeps dashboard publishing simple).
