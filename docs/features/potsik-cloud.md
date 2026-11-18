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
| No recognizer on the device | the voice button is dead (alpha 0.38); its `contentDescription` says why, and the error pill repeats it if the mode is started anyway |
| Nothing recognised | «Не расслышал, повторите», listening again — not an error |
| 15 s without an answer | «Не расслышал» + [Повторить] |
| The microphone permission is missing | «Нет доступа к микрофону» + [Повторить]; the dialog itself lives in the app, an overlay cannot raise it |
| The screen is off | «Слушаю только при включённом экране» — the microphone is not started |
| The question could not be sent | the transcript stays on the pill marked as not sent, [Повторить] |

## Flow 2 — text

The field is **not** in the overlay. `TYPE_APPLICATION_OVERLAY` is always drawn
below the keyboard, so a field inside the capsule would be covered the moment
the keyboard appeared. Instead the capsule opens a floating translucent activity
(`PotsikTextInputActivity`: `Theme.Translucent.NoTitleBar`, `windowIsFloating`,
`adjustResize`) with the field pinned to the bottom: the keyboard lifts it, back
closes it, and the insets are the system's own business.

- An empty field sends nothing (the button is disabled).
- The draft lives in `PotsikCloudDraft`, process-wide: closing the field with
  back or tapping away does not throw away what was typed.
- The answer is shown up to six lines with a scroll, with [Озвучить] when a
  synthesizer is available and [Открыть в чате] for the full history.
- A question that could not be sent stays in the field and is marked as not
  sent, with [Повторить].
- The microphone has exactly one owner: while the overlay's voice loop runs,
  [Озвучить] says «Сначала остановите голосовой режим» instead of talking over
  it.

## Where the code is

| File | What it is |
|---|---|
| `composeApp/src/commonMain/.../ui/potsik/PotsikCloud.kt` | The state, the placement arithmetic, the tap counter, the draft |
| `composeApp/src/commonMain/.../ui/potsik/PotsikCloudCapsule.kt` | The capsule and the pill (Compose, no platform code) |
| `composeApp/src/commonMain/.../ui/potsik/PotsikScreen.kt` | The screen mode of the third button — see [the screen mode](potsik-screenshot.md) |
| `composeApp/src/commonMain/.../ui/potsik/PotsikCloudAsker.kt` | One question into the current chat, one answer out of it |
| `composeApp/src/androidMain/.../overlay/PotsikCloudOverlay.kt` | The window, the microphone, the lifetime |
| `composeApp/src/androidMain/.../overlay/PotsikTextInputActivity.kt` | The text field, as a translucent activity |
| `composeApp/src/androidMain/.../overlay/PotsOverlayService.kt` | The character's window; owns the cloud's lifetime |
| `composeApp/src/commonTest/.../ui/potsik/PotsikCloud*Test.kt` | Placement and state rules, no device needed |

## What is not here

- No «Открыть Pots» action in the capsule (a v1.1 candidate).

The third button, «Экран», is [its own page](potsik-screenshot.md): it raises a
system consent dialog, and a dialog on every tap is a feature nobody uses — so
it has a mode of its own, with its own lifetime.
