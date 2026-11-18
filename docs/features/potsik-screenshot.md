# The screen mode — «Экран»

The third button of the cloud: one frame of whatever is on the screen, then a
question about it. The picture and the question go to the current chat as one
message, exactly like a photo picked from the gallery.

The feature only exists where the cloud does (Android 13+, «Поверх окон» — see
[Потсики](potsiki.md)). `MediaProjection` itself is available from API 21, but
a screenshot with a question about it needs the overlay to show the answer, so
the Android 13 gate of the overlay already covers it and no extra branch was
added.

## The flow

| Step | What the user sees |
|---|---|
| Tap «Экран» | No session yet → the system consent dialog; the pill says «Нужно разрешение на снимок экрана» and offers no buttons |
| Dialog answered yes | One frame is taken; the pill shows a 64×64 dp thumbnail, «Скриншот 1080×2400» and [×] |
| Tap the pill | The question field of Flow 2, with the placeholder «Спросить про этот экран…» and three chips above it: «Что здесь?» · «Переведи» · «Что не так?» |
| Send | The picture and the text go to the current chat in one turn; `Thinking` → `WORK_THINKING` |
| Answer | The text in the pill with [Озвучить] and [Открыть в чате] |
| Dialog refused | `errorContainer`: «Без разрешения снимок недоступен» + [Повторить]; the cloud stays open |

A chip **fills the draft** instead of sending at once: the question is the
user's, and the field is where it is written.

## Consent is granted once per mode

The system dialog on every tap is a feature nobody uses, so the session is
created once and lives while the cloud is open:

- the first «Экран» in a cloud raises the dialog; every later tap in the same
  cloud takes a frame from the session that already lives — no dialog;
- [×] on the shot pill removes the frame but **not** the session: the next tap
  is instant;
- closing the cloud (a tap outside it, [×], a second double tap, the app coming
  back, a rotation, the screen going off) ends the mode: the projection is
  released, the foreground service stops, the notification and the status-bar
  chip go away.

The next cloud therefore asks for consent again. That is not an oversight: the
`MediaProjection` token is **one-shot**. `createVirtualDisplay()` may be called
exactly once per consent, and a second call — or reusing the consent intent —
throws `SecurityException` on Android 14+. The mode keeps the virtual display
alive and reads a frame from it on demand; that is why repeated taps are free
and a new cloud costs a new consent.

`MediaProjection.Callback` is registered **before** `createVirtualDisplay` (the
platform throws `IllegalStateException` otherwise) and is what tells the cloud
that the projection is gone: the lock screen (Android 15 QPR1+), the
status-bar chip, or the system taking it away for its own reasons. That shows as
«Доступ к экрану закрыт» + [Повторить], and nothing typed is thrown away.

## Android 14 and 15

- **Android 14+** offers «a single app» in the consent dialog by default, which
  is the wrong thing to offer for "what is on my screen". The whole display is
  asked for explicitly with
  `createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())`.
  The decision is the pure `potsikScreenCaptureRequest(sdkInt)`: on Android 13
  and below it returns the plain intent, which is all the platform has, and the
  screenshot works the same. The version check is inside that function, not
  around the feature.
- **Android 15 QPR1+** shows a chip in the status bar while the projection
  lives, and stops the projection when the screen is locked. The chip is the
  second reason the mode ends with the cloud: a projection with no surface on
  screen would be a chip nobody asked for.

## FLAG_SECURE: Потсик is not in his own screenshot

Both overlay windows — the character and the cloud — are created with
`FLAG_SECURE`. Without it the screenshot would show Потсик standing on it and
the capsule with the thumbnail of the previous screenshot in it, which reads as
a broken feature. The system composites those windows out of the capture.

The side effect is honest and intended: a screenshot of the desktop pet's own
window is impossible for anyone, which is also what keeps the capsule out of
screen recordings.

## The frame itself

The reader is created as `RGBA_8888` at the display's own size. The plane the
platform hands back is **not** `width * 4` bytes per row — the hardware aligns
rows to `rowStride`, usually wider than the picture. Reading the buffer as one
continuous block shears the image diagonally and tints it: the classic "the
screenshot is green noise" bug.

So the copy walks the rows itself (`potsikRepackRgba`): `width` pixels of
`pixelStride` bytes each, starting at `y * rowStride`. It is pure arithmetic on
`ByteArray`, tested on synthetic planes with a tight row stride, a padded one
and a wider pixel stride, with no device involved.

The first frame of a fresh mirror is often not there yet — the display has to
draw something first — so the reader is polled for up to a second before the
mode gives up and says «Не удалось снять экран».

The picture is written to the app cache as a PNG and handed to the chat as a
`PlatformFile`, the same type the picker and the camera produce. The chat's own
pipeline classifies, compresses and attaches it, so the message in the history
is an ordinary message with a picture — no second message format.

## Where the code is

| File | What it is |
|---|---|
| `composeApp/src/commonMain/.../ui/potsik/PotsikScreen.kt` | The plane copy, the size caption, the consent decision, the state machine |
| `composeApp/src/commonMain/.../ui/potsik/PotsikCloudCapsule.kt` | The third button, the shot pill, the chips |
| `composeApp/src/androidMain/.../overlay/PotsikScreenCaptureActivity.kt` | The consent dialog, as an activity (only an activity gets a result) |
| `composeApp/src/androidMain/.../overlay/PotsikScreenCaptureService.kt` | The session: `mediaProjection` foreground service, one virtual display, one frame on demand |
| `composeApp/src/androidMain/.../overlay/PotsikScreenCapture.kt` | The channel between the three, and the projector over the service |
| `composeApp/src/androidMain/.../overlay/PotsikCloudOverlay.kt` | What the pill shows, and when the mode ends |
| `composeApp/src/commonTest/.../ui/potsik/PotsikScreenShotTest.kt` | Row strides, the caption, the consent decision |
| `composeApp/src/commonTest/.../ui/potsik/PotsikScreenModeTest.kt` | The whole cycle against a fake projector |

## What is not here

- **Downscaling** for providers with a small image limit (the spec's «Сжато до
  1440»): the chat's pipeline already compresses images, and a second cap would
  need a measured limit per provider. Its own card if it turns out to matter.
- **The black frame** of an app that forbids screenshots (`FLAG_SECURE` under
  us — banking, DRM) is not told apart from an empty one: both end in «Не
  удалось снять экран». Telling them apart needs a heuristic on the pixels, and
  guessing wrong is worse than the honest message.
