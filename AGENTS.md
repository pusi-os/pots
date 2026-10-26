# Pots — agent guide

Pots is the open-source AI assistant made by **Pusi**. The company is **Pusi**,
the app is **Pots**, and the character is **Потсик**. Repository:
`https://github.com/pusi-os/pots` (lowercase).

Kotlin Multiplatform + Compose Multiplatform, Koin DI, Ktor, SQLDelight, and
`nl.marc-apps:tts`. Targets: Android, iOS, Desktop JVM (Windows/macOS/Linux),
and Web (wasmJs).

## Product status

- **Потсик** is implemented. The character is drawn in code (no images, no
  Lottie) under `composeApp/.../ui/potsik/`; settings live in the **Потсики**
  tab. See `docs/features/potsiki.md`.
- **HandsFree** two-way voice dialogue is implemented on Android only, because
  only Android has a speech recognizer wired up. See
  `docs/features/hands-free.md`.
- **Mini-app studio** is implemented on Interactive UI. Saved apps live in
  SQLite plus a per-app user-storage folder, have a version tree,
  export/import, a standalone runner, and web apps served by the loopback
  server. See `docs/features/mini-apps.md` and
  `docs/features/mini-app-server.md`. "Backend" means declared data
  collections, records, and chat-driven automations, not a compiled server.
- **ESP32 companion** is planned but **not implemented**. Do not claim
  otherwise.

## Commands (JDK 21, `./gradlew`)

- Android debug APK: `./gradlew :androidApp:assembleFossDebug`
- Android release: `./gradlew :androidApp:assembleFossRelease`
- Unit tests (CI): `./gradlew :composeApp:desktopTest`
- Format: `./gradlew spotlessApply`
- Desktop run: `./gradlew :composeApp:run`
- Desktop release smoke: `./gradlew :composeApp:runRelease` — checklist in
  `docs/desktop-release-smoketest.md`
- README screenshots: `./gradlew :screenshotTests:updateScreenshots`
- Store screenshots: `./gradlew :screenshotTests:generateStoreScreenshots`
- Publish APK to the dashboard: `scripts/publish-apk.sh`

CI order matters: `spotlessApply` → `updateScreenshots` → `desktopTest`.

JDK 21 is required (Paparazzi refuses JDK 17). Point
`org.gradle.java.home` at a JDK 21 in `~/.gradle/gradle.properties`.

## Architecture

- Modules: `:composeApp` (shared KMP library + desktop/wasm targets),
  `:androidApp` (thin shell, flavors `foss` default and `playStore`),
  `:iosApp` (Xcode, Swift bridge `PotsLiteRTBridge.swift`), and
  `:screenshotTests` (Paparazzi goldens).
- `jvmShared/` is shared by `androidMain` and `desktopMain`. Platform behavior
  goes through `expect/actual` (`createDaemonController`,
  `SandboxController`, `PotsBuildController`, `LocalInferenceEngine`,
  `Platform.*`, `createSpeechToText`).
- DI graph: `composeApp/.../AppModule.kt`. ViewModels are registered there.
  `SettingsViewModel` and `ChatViewModel` are the two main entry points.
- SQLDelight uses `verifyMigrations=true`: schema changes require a numbered
  `.sqm` migration next to `conversation.sq`. Message *content* is JSON, so
  adding a field to `Conversation.Message` needs no migration.
- ProGuard/R8: shared `composeApp/proguard-rules.pro` plus desktop-only
  `proguard-desktop.pro`. BouncyCastle is restored post-ProGuard in
  `composeApp/build.gradle.kts` (do not remove). FreeTTS and `nl.marc-apps:tts`
  stay wholesale — see the smoketest doc.
- iOS: the framework must stay dynamic (`isStatic=false`; LiteRT `-all_load`
  conflict), with a bundle id distinct from the app id. CLiteRTLM dSYM comes
  from an Xcode build-phase script.

## Build secrets and signing

The free tier API key is **not** committed. It is read from the git-ignored
`local.properties` property `pots.free.apiKey` and baked into
`com.pusios.pots.BuildSecrets.FREE_TIER_API_KEY`. A key entered in Settings
wins over the bundled one.

Release signing uses `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, and `KEY_ALIAS`. If
they are absent, release builds use debug signing. `scripts/publish-apk.sh`
pins `ANDROID_USER_HOME=$HOME/.android`, deletes stale APKs before building,
and verifies the APK certificate. Do not bypass that check when publishing.

## Branding rules

- **Pusi** is the company; **Pots** is the app; **Потсик** is the character.
- Use `https://github.com/pusi-os/pots` with a lowercase `pots`.
- Community and support: `https://t.me/nerosekta`.
- Do not reintroduce old upstream names, old repository URLs, or old sponsor
  buttons. The GitHub sponsor entry was replaced by the NeuroSekta Telegram
  action.
- The only repository branding link is `pusi-os/pots`. Package ids, flatpak
  app ids, and desktop identifiers remain as installed (`com.pusios.pots`,
  `io.github.pusi-os.Pots`).

## Docs to keep in sync

- Touching a documented area → update `docs/features/<area>.md` (behavior prose,
  no Kotlin blocks), bump its `Last verified` date, and keep its Key Files table
  current.
- Knowledge bundles `docs/knowledge/{free-tier,model-catalog,popular-mcp,litert}/`
  mirror runtime files (`FreeTierModels.kt`, `ModelCatalog.kt`,
  `PopularMcpServers.kt`, `LocalModelCatalog.kt`). Refresh both via the matching
  `/update-*` command, never the runtime file alone.
- `README.md` is the public Russian entry point. Keep it accurate when
  features, platforms, commands, or links change.
