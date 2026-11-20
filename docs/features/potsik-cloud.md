# The cloud (облачко) on a double tap

A capsule that opens on a double tap on Потсик while he stands on the desktop,
in a system overlay window, and turns into the voice pill or the text field.

This is the Android side of the character: it only exists where the «Поверх
окон» mode works (Android 13+, `TYPE_APPLICATION_OVERLAY`, see
[Потсики](potsiki.md)). On every other platform, and on older Android, there is
nothing to double tap.

## The gesture

A tap on the character pets him — the behaviour the overlay shipped with. A
second tap inside the platform's double-tap window (`ViewConfiguration.getDoubleTapTimeout()`,
not a constant of ours) opens the capsule and takes back the line the first tap
brought up.

The double tap is counted **by hand**, in the same `detectTapGestures` block the
petting uses. Compose's own `onDoubleTap` cannot be used: with it the gesture
detector holds *every* tap for the whole double-tap window before answering, so
petting would start answering with up to 300 ms of lag. Counting by hand keeps
the first tap instant and the second one a menu.

Three quick taps are one double tap plus a fresh single tap, because the pair is
spent once it is recognised.

## The window

The capsule is its own overlay window, exactly the capsule's size (176×64 dp
plus a 12×8 dp tail), never a bigger window with empty areas: the platform
delivers every tap inside a window's rect to its owner, transparent pixels
included, so a roomier window would eat taps meant for the app underneath. The
old trick of one full-screen transparent window with `FLAG_NOT_TOUCHABLE` is
not an option — it stopped working on Android 12.

Placement, all of it pure and tested without a device
(`PotsikCloudPlacementTest`):

| Situation | Where the capsule goes |
|---|---|
| Room above his head | centred on the head, its bottom 8 dp above the top of it |
| He is dragged near the top edge | to the right if the capsule plus its gap fits, otherwise to the left |
| Neither side fits | back above, clamped |
| Every edge | clamped with 8 dp of room; he can be dragged past the edge, the capsule cannot |

Never below him: from below the capsule covers his body and his own speech
bubble.

## The menu

Three 48 dp actions in the spec's 176×64 capsule: «Голос» and «Текст» are filled
tonal (`primaryContainer`), «Экран» is **outlined** — the spec's secondary action
for the one button that raises a system dialog, and last in the row so a stray
tap near the edge does not start a screen capture
(`pots-overlay-menu-ux.md` §7).

Without a recognizer the voice button is dead (grey glyph, no shape, alpha 0.38)
and the menu carries the explanation **above** the capsule, growing the window
by 36 dp (`POTSIK_CLOUD_NOTICE_HEIGHT_DP`). Above, not under the tail: the
window's bottom edge is anchored 8 dp above his head, so a line below the tail
would pull the tail off the head. `potsikCloudWindowSize(state, voiceAvailable)`
decides that height and is tested without a device.

## Lifecycle

The capsule is taken down when:

- a drag of the character starts (a capsule following a moving window judders) —
  it fades out over 90 ms rather than vanishing from under the finger;
- the app comes to the foreground (`applyVisibility`: one Потсик, one surface);
- the screen goes off (the microphone stops with it);
- the screen rotates (`onConfigurationChanged` closes it and re-clamps the
  character; the next double tap opens it on the new bounds);
- a tap lands outside the capsule, on [×], or on a second double tap.

## Flow 1 — voice

The pill is `HandsFreeController` in a window: `Listening` → `Thinking` →
`Speaking` → `Listening`, the same loop the chat runs, with the same 15-second
watchdog and the same barge-in order (speech stops first, the microphone opens
after).

The microphone is reached from the pet's foreground service, so on Android 14+
the service has to carry the `microphone` foreground-service type — declared in
the manifest **and** passed to `startForeground`. `PotsOverlayService` does both
(`specialUse|microphone`): the window is the special use, the listening is the
microphone. Without it `startListening` is accepted, the pill says «Слушаю…» and
nothing is ever recorded — exactly the defect the owner reported.

The microphone type is *attempted*, never assumed: it is a while-in-use
permission, so `startForeground` throws `SecurityException` when `RECORD_AUDIO`
has not been granted yet, and when the service is restarted from the background
(`START_STICKY` after a process death). The service catches it, logs under
`PotsBuild`, and starts again with the types that need no permission
(`specialUse` alone on API 34+, the two-argument call below) — the pet stays on
the desktop and the voice pill honestly says «Нет доступа к микрофону».

Every recognition session gets a fresh `SpeechRecognizer` instance, and every
failure is logged with its numeric code under the `PotsStt` tag: `cancel()` is
asynchronous, so reusing one instance made the second «Голос» of a session answer
`ERROR_RECOGNIZER_BUSY`, and the mapped enum alone could not say why.

| State | Pill | Character |
|---|---|---|
| `Listening` | pulse + three bars + «Слушаю…» | `IDLE_BREATHE` with the idle roll's own lean |
| `Thinking` | «Думаю…» + three dots, [Отмена] after 3 s | `WORKING` |
| `Speaking` | two lines of the answer + [Стоп] | `SPEAKING` |
| `Error` | one line of the reason + [Повторить] | `ERROR` |

The character is driven by the app's own `PotsikPhase` — no new poses were
added for the cloud.

Failure branches, all of them keeping what the user said:

| Situation | What the user sees |
|---|---|
| No recognizer on the device | the voice button is dead (alpha 0.38, `contentDescription` «Голосовой режим недоступен») and the line above the capsule says «На этом устройстве нет распознавания речи». The mode cannot be started, so no error pill is reachable from here; `NoRecognizer` is left for a recognizer that fails mid-session |
| Nothing recognised | «Не расслышал, повторите», listening again — not an error |
| 15 s without an answer | «Не расслышал» + [Повторить] |
| The microphone permission is missing | «Нет доступа к микрофону» + [Повторить]; the dialog itself lives in the app, an overlay cannot raise it |
| The screen is off | «Слушаю только при включённом экране» — the microphone is not started |
| The question could not be sent | the transcript stays on the pill marked as not sent, [Повторить] |

## Flow 2 — text

«Текст» turns the capsule into a field: the cloud stays where it is, the window
grows to `POTSIK_CLOUD_INPUT_WIDTH_DP` × `POTSIK_CLOUD_INPUT_HEIGHT_DP`
(288 × 80 dp — one row, taller than the menu's 72 dp), takes input focus
and the keyboard comes up. The answer to a typed question appears on the same
pill the voice loop uses — `Thinking` → `Speaking` — because the question goes
through `PotsikCloudAsker`, the one send path both flows share. The app is never
opened.

The field is the only phase that removes `FLAG_NOT_FOCUSABLE`: without that the
window never receives the IME. Everything else keeps the flag, so the menu and
the pills do not steal the keyboard (or the back key) from the app underneath.

`TYPE_APPLICATION_OVERLAY` is always drawn **below** the keyboard, and no inset
ever reaches an overlay — so `adjustResize` cannot save a field that sits in the
bottom half. `potsikCloudInputTop` is the lever: the window is raised so its
bottom stays above `POTSIK_CLOUD_KEYBOARD_TOP_FRACTION` (half) of the screen. The
capsule already lives above his head, so in the usual case the clamp does
nothing; it only fires when he was dragged into the lower half.

- An empty field sends nothing (the button is disabled, and the keyboard's
  «Отправить» action does nothing).
- The draft lives in `PotsikCloudDraft`, process-wide: closing the field with
  [×] or a tap outside does not throw away what was typed.
- Only the field's own question clears it: `onSendText` marks the question it
  sent, and an answer to a voice or screenshot question leaves the draft alone.
- A question that could not be sent stays as the draft and is marked as not
  sent on the pill, with [Повторить].
- One tap of [Отправить] is one question. The tap returns at once — the frame's
  write is off the main thread — so a second tap lands while the first question
  is still being prepared; the window raises `sending` before the ask and lowers
  it in the coroutine's `finally`, so the second tap does nothing instead of
  becoming a second request the provider bills for. A failed send lowers the
  flag too: the user must be able to tap again.
- The microphone has exactly one owner: the cloud's voice loop and the field are
  never up at the same time.

The translucent activity (`PotsikTextInputActivity`) is still what the **screen**
mode opens for its question about the frame ([the screen mode](potsik-screenshot.md)):
that panel carries the thumbnail, the chips and [Открыть в чате], and it is a
full activity by design.

## Where the code is

| File | What it is |
|---|---|
| `composeApp/src/commonMain/.../ui/potsik/PotsikCloud.kt` | The state, the placement arithmetic, the tap counter, the draft |
| `composeApp/src/commonMain/.../ui/potsik/PotsikCloudCapsule.kt` | The capsule and the pill (Compose, no platform code) |
| `composeApp/src/commonMain/.../ui/potsik/PotsikScreen.kt` | The screen mode of the third button — see [the screen mode](potsik-screenshot.md) |
| `composeApp/src/commonMain/.../ui/potsik/PotsikCloudAsker.kt` | One question into the current chat, one answer out of it |
| `composeApp/src/androidMain/.../overlay/PotsikCloudOverlay.kt` | The window, the microphone, the lifetime |
| `composeApp/src/androidMain/.../overlay/PotsikTextInputActivity.kt` | The question about a screenshot, as a translucent activity |
| `composeApp/src/androidMain/.../overlay/PotsOverlayService.kt` | The character's window; owns the cloud's lifetime |
| `composeApp/src/commonTest/.../ui/potsik/PotsikCloud*Test.kt` | Placement and state rules, no device needed |

## What is not here

- No «Открыть Pots» action in the capsule (a v1.1 candidate).

The third button, «Экран», is [its own page](potsik-screenshot.md): it raises a
system consent dialog, and a dialog on every tap is a feature nobody uses — so
it has a mode of its own, with its own lifetime. The frame is the screen the
user was on when the tap happened: the consent activity lives in a task of its
own and does not bring the app to the front, Потсик's windows are composited out
of the capture, and the first frame waits for the screen to settle.
