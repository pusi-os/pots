# App update and onboarding replay

**Last verified:** 2026-11-17

Two deliberate, one-shot actions live in **Settings → Services**, in the card directly under
PotsGame: replaying the first-launch wizard and updating Pots from its own GitHub releases.
Neither runs on its own — an idle settings screen never touches the network and never
downloads anything.

## Where the buttons are

The section is the last card of the Services tab, right after the PotsGame club card. It holds
the «Онбординг» button, the «Обновить приложение» button and, once an update is in flight, the
progress area for that update.

## Onboarding replay

«Онбординг» reopens the first-launch wizard from its first step, whether the user finished it,
skipped it or never saw it. The action reuses the wizard's existing persistence — the same
`DONE` / `STEP` / `FINISHED_STEP` flags the wizard itself writes — so there is no second source
of truth about onboarding state. Because the wizard renders in the empty-chat slot, pressing the
button starts a fresh chat and navigates back to it; the previous conversation stays in history.

## Update source

The check reads `GET /repos/pusi-os/pots/releases/latest` and takes the single newest published
release: GitHub already excludes drafts and pre-releases from that endpoint. Only the assets of
that release are considered; the release notes are not shown in the app.

### Version comparison

The comparison is numeric, never lexical. A GitHub release publishes only its tag (`v3.13.1`) —
the APK's Android `versionCode` never leaves the build — so both sides are encoded through the
same scheme: `major * 1_000_000 + minor * 1_000 + patch`, with minor and patch required to stay
below 1000. `3.9.0` therefore sorts below `3.13.0`, which a string comparison gets backwards.
A local version string that cannot be encoded (a development build, for example) refuses to
compare instead of guessing, and the update reports an unreadable-version error.

### Asset selection

Assets follow the release job's naming: `Pots-<version>-<os>.<ext>`, plus
`Pots-<version>-linux-<arch>.tar.gz` for the tarball, which carries an architecture token between
the OS and the extension. Selection picks one asset for the running OS and CPU family:

- Android → `.apk`
- Windows → `.msi`
- macOS → `.dmg`
- Linux → `.AppImage`, then `.deb`, `.rpm`, `.flatpak`, `.tar.gz`

An asset built for another CPU family is dropped rather than ranked, so an arm64 host never
receives the x86_64 tarball. Names without an architecture token are treated as
architecture-neutral, which matches how the `.deb`, `.rpm`, `.AppImage` and `.flatpak` builds are
published. A release with no build for the running platform reports that instead of falling back
to a foreign container.

## Update flow

The phases the UI reports, in order:

1. **Checking** — the release request. Nothing is downloaded yet.
2. **Downloading** — the asset streams to disk in chunks, with a percentage and a byte/second
   rate. The transfer can be cancelled at any point; a cancelled or failed download deletes its
   partial file.
3. **Unpacking** — only for archive containers (`.tar.gz`, `.zip`, `.flatpak`). The container
   header is validated here, so the app never hands a proxy error page to an installer.
4. **Installing** — the file is handed to the platform installer: the APK package installer on
   Android, the OS handler for `.dmg`/`.msi`/`.deb`/`.rpm`/`.AppImage` on desktop.
5. **Done** — the update needs a restart. When no handler is registered for the container, the
   app says so and points at the saved file instead of pretending the install started.

iOS and the web build cannot self-update — iOS installs through the App Store and the web build
has no filesystem — so the button is disabled there with a one-line explanation.

Before a byte is requested the app checks free space on the target volume, keeping a fixed
headroom above the download size so a full disk cannot swallow the database. A transfer that
lands fewer bytes than the release declares is rejected as incomplete.

## Errors

Every failure ends with a concrete next step rather than an exception name: no connection,
GitHub rate limiting (wait, or download from the releases page), release not found, server error,
unreadable response, not enough space, interrupted transfer, corrupted container, no build for
this platform, and self-update unsupported. A failed run can be retried from the same button.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../update/AppUpdate.kt` | Release parsing, numeric version encoding, asset selection, container header checks, error taxonomy |
| `composeApp/src/commonMain/.../update/AppUpdateManager.kt` | Release check and chunked download with progress, speed, disk-space guard and cancellation |
| `composeApp/src/commonMain/.../update/UpdatePlatform.kt` | Platform contract: update file sink, host architecture, installer hand-off |
| `composeApp/src/jvmShared/.../update/UpdatePlatform.jvmShared.kt` | Android/desktop sink and architecture detection |
| `composeApp/src/desktopMain/.../update/UpdateInstaller.desktop.kt` | Hands the file to the desktop OS handler |
| `composeApp/src/androidMain/.../update/UpdateInstaller.android.kt` | Hands the APK to the package installer through the existing FileProvider opener |
| `composeApp/src/commonMain/.../ui/settings/AppUpdateUiState.kt` | Update phases and the state the settings section renders |
| `composeApp/src/commonMain/.../ui/settings/ServicesSettings.kt` | `AppMaintenanceSection` — the two buttons and the progress area |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Drives the check, the download and the installer hand-off |
| `composeApp/src/commonMain/.../ui/chat/ChatViewModel.kt` | `restartOnboarding()` — reopens the wizard on a fresh chat |
| `composeApp/src/commonTest/.../update/` | Mock-HTTP tests: parsing, version order, asset selection, every error branch |
