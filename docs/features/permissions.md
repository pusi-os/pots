# Runtime permissions

**Last verified:** 2026-11-19

Every permission Pots asks for at runtime is one value of `AppPermission`
(`composeApp/src/commonMain/.../tools/PermissionController.kt`). The enum is the single
source of truth: the Android actual maps each value to the manifest permissions it needs,
the app root mounts one launcher per value, and tools ask their controller before they
touch anything.

## How it works

1. **`AppPermission`** names a capability, not a manifest string: `CALENDAR`, `POST_NOTIFICATIONS`,
   `READ_SMS`, `SEND_SMS`, `LOCAL_NETWORK`, `RECORD_AUDIO`, `READ_MEDIA_IMAGES`, `CONTACTS`.
2. **`PermissionController`** (common) wraps one value: `hasPermission()`, a suspending
   `requestPermission()` that waits up to 60 s for the user's answer, and `openAppSettings()`.
3. **`SetupPermissionHandler`** is called once per value in `App.kt` (`AppPermission.entries.forEach`),
   so a new enum value gets its launcher without touching the UI tree.
4. **Platform actuals** answer `platformHasPermission` / `platformCanRequest`. A value that
   isn't gated on a platform returns an empty permission array, which reads as "granted" and
   suppresses the prompt — that is how iOS, desktop and web stay free of Android dialogs.
5. **Tools** call the controller at the top of `execute()`. A denial is answered as data
   (`success: false` plus a sentence the model can act on), never as an exception.

`permissionsFor()` in `PermissionController.android.kt` is an exhaustive `when`, so adding a
value to the enum fails the Android build until the mapping is written — deliberately.

## Current map

| `AppPermission` | Manifest permission(s) | Gated on | Used by |
|---|---|---|---|
| `CALENDAR` | `READ_CALENDAR`, `WRITE_CALENDAR` | Android, all versions | calendar tools |
| `POST_NOTIFICATIONS` | `POST_NOTIFICATIONS` | Android 13+ | `send_notification` |
| `READ_SMS` / `SEND_SMS` | `READ_SMS`, `SEND_SMS` | FOSS flavor only | SMS tools ([sms.md](sms.md)) |
| `LOCAL_NETWORK` | `ACCESS_LOCAL_NETWORK` | Android 17+ (`targetSdk` 37) | reaching self-hosted servers |
| `RECORD_AUDIO` | `RECORD_AUDIO` | Android | hands-free voice mode |
| `READ_MEDIA_IMAGES` | `READ_MEDIA_IMAGES` (≤ Android 12: `READ_EXTERNAL_STORAGE`) | Android | `search_photos`, `import_photo` |
| `CONTACTS` | `READ_CONTACTS`, `WRITE_CONTACTS` | Android, all versions | `contacts_search`, `contact_create` |

## Foreground-service types

Not runtime permissions, but the same kind of gate on Android 14+: a background
service reaches the microphone, the screen or a long-running sync only through
the type it declares in the manifest **and** passes to `startForeground`. A type
in the manifest that is not passed at runtime is not granted.

| Service | Type | Why |
|---|---|---|
| `PotsOverlayService` | `specialUse\|microphone` | the desktop pet window, and the cloud's voice loop that records from it |
| `PotsikScreenCaptureService` | `mediaProjection` | the screen mode's MediaProjection session |
| `SandboxBackgroundService` | `specialUse` | the local Linux sandbox staying alive |
| `ModelDownloadService` | `dataSync` | model downloads |

## Contacts

`READ_CONTACTS` and `WRITE_CONTACTS` are ordinary dangerous permissions, not hard-restricted,
and they have existed since API 5 — far below the app's `minSdk`, so there is no version gate
to choose. Both are requested together as one `AppPermission.CONTACTS`, because the tools ship
as a set and the system shows one dialog per group anyway.

`contact_dial` needs no permission at all: `Intent.ACTION_DIAL` opens the dialer with the
number filled in and the user presses the call button. `CALL_PHONE` is deliberately **not**
declared — the AI never places a call.

### Why not the Android 17 Contacts Picker

`ContactsPickerSessionContract` / `ACTION_PICK_CONTACTS` exists on API 37 and would avoid
`READ_CONTACTS`, but it is an *activity where the user picks contacts by hand*. `contacts_search`
has to find a contact by name on its own, without the user in the loop, which the picker cannot
do. It is also only present on API 37 devices. The tools therefore use `ContactsContract`, and
privacy is enforced on the data instead: a required query, at most 10 results, only
`id`/`name`/`phone`, and no logging.

### The settings row

**Settings → Tools**, directly above the tool list: title, a one-line explanation, a status dot
("Access granted" / "No access to contacts") and an "Allow access" button that shows the system
prompt. The row re-reads the state on every resume, so granting the permission in system settings
and coming back updates it. After a refusal the button turns into "Open settings": Android will not
show the dialog a second time once the user dismissed it with "don't ask again", and a button that
silently does nothing would leave the user stuck. On iOS, desktop and web the row is not rendered at
all — those platforms have no contact tools.

## Adding a permission

1. Add the value to `AppPermission` with a comment saying what needs it.
2. Add the branch to `permissionsFor()` in `PermissionController.android.kt` (the compiler
   demands it) and to `platformHasPermission` in the iOS, desktop and wasmJs actuals.
3. Declare the manifest permission in `androidApp/src/main/AndroidManifest.xml` — or in
   `androidApp/src/foss/AndroidManifest.xml` when Play policy forbids it in the store build.
4. Gate the tool or the UI on `controller.hasPermission()` and answer a denial as data.
5. Add a settings row next to the feature it unlocks; hide it where the feature doesn't exist.
