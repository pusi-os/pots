# Daemon Mode

**Last verified:** 2026-11-19

Pots's daemon mode keeps the app running in the background on Android so that scheduled tasks, heartbeat checks, and email polling continue to execute even when the app is not in the foreground. On other platforms (desktop, iOS, web), daemon mode is a no-op.

## Concepts

### Daemon Controller

A platform-abstracted start/stop switch. On Android, it starts and stops an Android foreground service. On all other platforms, it does nothing.

### Foreground Service

An Android service that runs with a persistent notification, preventing the system from killing the process. Pots's service uses the `specialUse` foreground service type with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification and a low-importance notification channel to minimize user disruption.

It used to be `dataSync`, which was wrong twice over: keeping the app process alive is not a sync, and on Android 15+ `dataSync` has a six-hour budget per 24 hours with an `onTimeout` callback that obliges the service to stop — a daemon meant to be up all day would have gone quiet every afternoon. `specialUse` has no time budget. The type arrived in API 34, so `startForeground` passes `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` from 34 up and is called without a type below that; the manifest attribute is simply ignored by older versions, which is why one service entry covers every supported OS.

## Service Lifecycle

1. When daemon mode is enabled in settings, the foreground service is started
2. The service creates a notification channel and displays a persistent "Daemon is running" notification
3. The service starts the task scheduler, which runs for the lifetime of the process (starting it again is a no-op)
4. The service is "sticky", so Android re-creates it if the system kills it
5. When daemon mode is disabled, the service is stopped and the foreground notification is removed
6. On Android 12+, starting the service can be refused when the app isn't in the foreground; the refusal is swallowed so the daemon can be retried later (e.g. on the next return to the foreground)
7. If the service cannot promote itself to the foreground once created, it stops itself immediately
8. There is no time budget to honour: `specialUse` is not one of the types Android 15 caps, so there is no `onTimeout` path and the service stays up until it is stopped or the process dies

## Auto-Start

Every time the main activity is brought to the foreground, if daemon mode is enabled in settings, the service is (re)started. This runs on app launch as well as on every subsequent return to the foreground, so the daemon recovers from situations where OEM battery managers or aggressive task killers have terminated the service while the app was in the background.

### Other Starters

Starting a Splinterlands auto-battle also starts the foreground service (regardless of the Daemon Mode toggle) so battles keep running in the background; stopping the battle does not stop the service.

## Background Work

The daemon's task scheduler polls every 60 seconds and handles four types of background work:

- **Scheduled tasks** — executes due tasks by sending their prompts through the AI pipeline
- **Heartbeat checks** — periodic self-checks during active hours (see heartbeat doc)
- **Email polling** — fetches new emails from configured accounts on a configurable interval
- **SMS polling** — checks for new incoming SMS messages on a configurable interval (FOSS builds only)

The scheduler only runs this work while the overall scheduled-tasks toggle is on, and skips a cycle's work while a foreground chat request is in flight.

## Notification

- **Channel**: "Pots Background Service" with low importance
- **Content**: "Daemon is running" with a sync icon
- **Tap action**: Opens the app's main screen
- The notification is required by Android for foreground services and cannot be hidden

## Permissions

The app declares these foreground-service permissions in the Android manifest:

- `FOREGROUND_SERVICE` — required for all foreground services
- `FOREGROUND_SERVICE_SPECIAL_USE` — required for the `specialUse` type the daemon uses
- `FOREGROUND_SERVICE_DATA_SYNC` — still declared, for `ModelDownloadService`, where the six-hour sync budget is the right ceiling

The notification permission (`POST_NOTIFICATIONS`) is also declared and is requested when daemon mode is switched on (see below). It decides whether the notification is *visible*, not whether the service may start: Android does not gate a foreground service on it.

No wake locks or battery optimization exemptions are requested for the daemon. (The sandbox's separate background mode does ask for the exemption, because a frozen app takes the sandbox down with it — see [sandbox.md](sandbox.md#background-mode).)

## Settings UI

A toggle labeled "Daemon Mode" appears in the General tab of settings, only on Android. The description reads: "Keep Pots running in the background so scheduled tasks execute even when the app is not in the foreground." Toggling it starts or stops the foreground service and persists the preference. On Android 13+, turning the toggle on also requests the notification permission, since the foreground service's persistent notification cannot be displayed without it.

## Key Files

| File | Purpose |
|---|---|
| `composeApp/src/commonMain/.../DaemonController.kt` | Platform-independent interface, plus the `NoOpDaemonController` every non-Android target returns |
| `composeApp/src/androidMain/.../DaemonController.android.kt` | Android implementation, start/stop/auto-start logic |
| `composeApp/src/androidMain/.../DaemonService.kt` | Android foreground service, notification, task scheduler startup |
| `androidApp/src/main/.../MainActivity.kt` | Auto-start (and recovery) on every foreground transition |
| `androidApp/src/main/AndroidManifest.xml` | Service declaration and permissions |
| `composeApp/src/commonMain/.../data/TaskScheduler.kt` | Background poll loop started by the service |
| `composeApp/src/commonMain/.../data/AppSettings.kt` | Daemon enabled state persistence |
| `composeApp/src/commonMain/.../ui/settings/GeneralSettings.kt` | Daemon mode toggle UI (General tab) |
| `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` | Toggle handling: persists the preference, requests notification permission, starts/stops the service |
| `composeApp/src/androidMain/res/values/strings.xml` | Notification channel name/description and "Daemon is running" text |
| `composeApp/src/commonMain/.../splinterlands/SplinterlandsBattleRunner.kt` | Starts the service when a battle begins |
