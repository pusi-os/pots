# Pots as the Android assistant

**Last verified:** 2026-11-19

What changes when Pots holds the system's assistant role, what the role buys,
and what stays the same without it.

The short version: the role gives Pots **the text of the screen at the moment
the assistant gesture is used**, and nothing else. Everything else — the
character, the cloud, the voice loop, the screenshot flow — works exactly the
same with the role and without it.

## Becoming the assistant

The role is `ROLE_ASSISTANT`, and Android does not let an app ask for it:
AOSP marks it `requestable="false"`, so there is no dialog to raise from code.
The user assigns it in the system's own screen — Settings → Apps → Default apps
→ **Digital assistant app** — which is what the settings card's
[Открыть приложения по умолчанию] button opens.

For Pots to appear in that list at all, the app has to declare a voice
interaction service:

| Piece | Where |
|---|---|
| `PotsVoiceInteractionService`, `BIND_VOICE_INTERACTION`, exported, `android.voice_interaction` metadata | `androidApp/src/main/AndroidManifest.xml` |
| `@xml/voice_interaction_service` — `sessionService`, `recognitionService`, `supportsAssist` | `androidApp/src/main/res/xml/` |
| `PotsVoiceInteractionSessionService` — one session per invocation | `composeApp/src/androidMain/.../assistant/` |
| `PotsVoiceInteractionSession` — reads the snapshot, opens the surface | same |
| `PotsAssistRecognitionService` — a stub the metadata has to name | same |

Two details are easy to miss and both are load-bearing:

* **`usesAssistData` and `usesAssistStructureScreenContent`** in the metadata,
  plus `READ_ASSIST_STRUCTURE_SCREEN_CONTENT` in the manifest. Pots targets
  SDK 37, and from there the platform no longer hands assist data to
  assistants implicitly — an app that does not declare the intent gets a
  session with an `ActivityId` and no content. The permission is `normal`:
  granted at install, no dialog. The user's own consent is the
  **«Use text from screen»** switch on the assistant screen, which is on by
  default.
* **`recognitionService`** must point at something. Android 12+ never calls
  it, but leaving it out makes the system reset the assistant on the app.
  Pots' stub answers every request with an error; the listening itself is the
  hands-free loop's recognizer.

Once the service is declared, the assist gesture stops reaching the
`ACTION_ASSIST` activity: the system routes it to the voice interaction
service instead. The Quick Settings tile still sends `ACTION_ASSIST` and keeps
working — that path is a plain activity launch, not a gesture.

## Activation: the gesture, and only the gesture

There is no wake word. The owner's decision, and the platform agrees with it:

* a **native hotword detector** would be `VoiceInteractionService`'s own
  always-on listening, which is `@SystemApi` — third-party apps cannot use it;
* a **custom one** (Vosk or any other local recognizer running all the time)
  means a permanently open microphone and a permanently running foreground
  service, for a feature the gesture already covers.

So Pots is invoked by the system gesture — long-press Home, swipe up from a
bottom corner, or the power-button assistant — exactly like any other
assistant. The user can also use the Quick Settings tile or the voice button
in the chat, which are ordinary entry points and do not need the role.

## The gesture opens Потсик already listening

An invocation is two things, in this order:

1. `onShow` — the gesture arrived. The system has bound the session like a
   visible foreground service and allowed it to start activities from the
   background, which is why the microphone can be opened from here at all.
2. `onHandleAssist` — the screen's text, asynchronously, a moment later.

The session waits up to 1.2 s for the snapshot and then opens one of two
surfaces:

* **Потсик is on the screen** (the «Поверх окон» mode is on, the character
  stands over somebody else's app) — the cloud opens over him, already
  listening, with the screen's text in hand. This is the surface the feature
  exists for: a question about what is on the screen, asked without leaving
  that screen.
* **There is no pet** (the mode is off, the overlay permission is gone, the
  app is in front) — the app opens through its own assist path: a fresh chat
  with the microphone already open. The snapshot is *not* carried over: the
  chat is a different conversation, and the screen of one moment has no place
  in a question asked later.

The microphone is the hands-free loop's own (`HandsFreeController`), the same
one the microphone button in the cloud uses. There is exactly one owner of the
microphone, and what the gesture does is decided by the loop's state
(`assistInvocationAction`, `commonMain`, tested on a desktop):

| Loop state | What the gesture does |
|---|---|
| `Idle`, `Error` | opens the microphone (`start()`) — the pill goes to Listening |
| `Speaking` | cuts the answer short (`interruptAndListen()`) and listens — the person invoked the assistant because they want to be heard |
| `Listening`, `Thinking`, `NoMatch` | does not touch the microphone at all: the snapshot is attached to the next question and the pill keeps its phase |

The third row is the rule that matters: `cancel()` is asynchronous, so starting
a new recognition session on top of a live one produced `ERROR_RECOGNIZER_BUSY`
on the microphone button before, and the same would happen here. `NoMatch` is in
that row because the loop reopens the microphone by itself two seconds later;
`Thinking` because the question has already been asked. `Error` is deliberately
*not* "in progress" — the loop has already stopped listening there, so a fresh
gesture is a fresh attempt.

`interruptAndListen()` exists for the second row: `start()` during speech would
raise the recognizer on top of a live synthesizer, and `stop()` + `start()` would
clear the transcript, drop to `Idle` and ask for the microphone permission again
on every gesture. It silences the voice first, then opens the microphone, which
keeps the "recognizer and synthesizer never run at once" invariant.

If the microphone permission was revoked, nothing crashes and nothing stays
silent: the pill says «Нет доступа к микрофону» and offers [Настройки], which
opens the app's system page — an overlay window cannot raise the permission
dialog itself.

## What the snapshot is, and what it is not

`AssistState.getAssistStructure()` is a **one-shot snapshot of the top
activity, taken when the assistant was invoked**. There is no public API to
re-read the screen later in the same session; the only way to refresh it is to
show the session again, which snapshots whatever is on top at that moment.

For Pots this is enough, and the UI says so: while the snapshot is attached to
the next question, the cloud carries the line **«Текст экрана снят при
вызове Потса»**. The snapshot is spent by the first question — a second
question a minute later is about a different screen, and pretending otherwise
would be a lie in the prompt.

What comes out of the snapshot (`assistScreenText`, `commonMain`, tested on a
desktop):

| Rule | Why |
|---|---|
| Pre-order, left to right | the order a screen reader reads the screen in |
| `text`, then `contentDescription`, then `hint` | an icon button has only the second, an empty field only the third |
| a blocked node hides its subtree | `importantForAssist=false` means "not for assist", and a child of it is just as much the app's content |
| a repeated line is kept once | the same word in a header and in a list row is one word of context |
| whitespace collapsed, empty lines dropped | a snapshot is full of layout text that says nothing |
| ≤ 1500 nodes, ≤ 6 KB, then `[… screen text truncated]` | a prompt, not a book; the mark says the model is looking at a part |

The node cap lives in one place, on purpose: `assistScreenText` stops *reading*
at 1500 nodes or 6 KB, whichever comes first, and that same decision adds the
`[… screen text truncated]` mark. The Android walk has no budget of its own —
two independent limits on one path is exactly how a snapshot gets trimmed by one
of them while the other says nothing about it. A snapshot with nothing readable
in it is `""`, never a bare mark: there is no text that was cut.

A `FLAG_SECURE` window arrives as an empty stub — there is nothing to read, and
nothing is invented. That is also why the cloud can open with no context at
all: the gesture is not a promise that the screen was readable.

The snapshot is attached to the question as a block **after** the user's words,
under a header (`assist_screen_context_header`), so the chat shows what was
sent and the person's question is not misquoted.

## Without the role: the screenshot flow

The screenshot path is unchanged and does not depend on the role:

«Экран» in the cloud → `MediaProjection` consent → a frame → a question about
the frame (`PotsikScreenCaptureService`, `PotsikScreenCapture`, see
[Потсики](potsiki.md)). It works with Pots as the assistant, with another app
as the assistant, and with no assistant assigned at all.

The mechanism is chosen by what is available, never by a stored flag
(`ScreenContextAvailability`, `commonMain`):

| Availability | Mechanism |
|---|---|
| `AssistSnapshot` — Pots holds the role | the screen's text at invocation |
| `Screenshot` — the role is not held | the MediaProjection flow |
| `NotOnThisPlatform` | nothing (desktop, iOS, web) |

The settings card says which one is in force, because the role's only real
benefit is exactly that: text instead of a picture.

## What is deliberately not here

* **No `AccessibilityService`.** It would give a live view tree in any moment,
  but it is a separate, heavy permission the user has to switch on in
  accessibility settings, with a system warning attached. A separate decision
  and a separate card if it is ever wanted.
* **No always-on listening.** See above.
* **No screenshot from the assist path.** `usesAssistScreenshots` and
  `onHandleScreenshot` exist, but the screenshot the app actually uses is the
  MediaProjection one — a second pipeline nobody reads would only add latency
  to every invocation.

## Key Files

| File | Purpose |
|---|---|
| `androidApp/src/main/AndroidManifest.xml` | the `VoiceInteractionService` declaration, `BIND_VOICE_INTERACTION`, `android.voice_interaction` metadata, `READ_ASSIST_STRUCTURE_SCREEN_CONTENT` |
| `androidApp/src/main/res/xml/voice_interaction_service.xml` | `<voice-interaction-service>`: session service, recognition stub, `supportsAssist`, `usesAssistData`, `usesAssistStructureScreenContent` |
| `composeApp/src/androidMain/.../assistant/PotsVoiceInteractionService.kt` | the service the system binds; no logic of its own |
| `composeApp/src/androidMain/.../assistant/PotsVoiceInteractionSessionService.kt` | one session per invocation |
| `composeApp/src/androidMain/.../assistant/PotsVoiceInteractionSession.kt` | waits for the snapshot, then hands the invocation over |
| `composeApp/src/androidMain/.../assistant/PotsAssistInvocation.kt` | picks the surface: the cloud over the pet, or the app's own assist path |
| `composeApp/src/androidMain/.../assistant/AssistStructureText.kt` | the `AssistStructure` walk: builds `AssistNode`s, no limits of its own |
| `composeApp/src/androidMain/.../assistant/PotsAssistRecognitionService.kt` | the stub the metadata has to name; answers every request with an error |
| `composeApp/src/commonMain/.../assistant/AssistScreenText.kt` | `assistScreenText()` — the pure rules (order, dedup, blocked subtrees, limits, mark) |
| `composeApp/src/commonMain/.../assistant/AssistInvocationPolicy.kt` | `assistInvocationAction()` — what the gesture does to the microphone, by loop state |
| `composeApp/src/commonMain/.../handsfree/HandsFreeController.kt` | the voice loop; `interruptAndListen()` is the gesture's entry during speech |
| `composeApp/src/commonMain/.../assistant/ScreenContextAvailability.kt` | which mechanism is in force, by what is available |
| `composeApp/src/commonMain/.../assistant/AssistantAvailability.kt` | whether Pots can hold the role at all (API level, whether it does) |
| `composeApp/src/androidMain/.../overlay/PotsOverlayService.kt` | opens the cloud from an invocation |
| `composeApp/src/androidMain/.../overlay/PotsikCloudOverlay.kt` | the cloud's window, the microphone, the assist snapshot's lifetime |
| `composeApp/src/commonMain/.../ui/potsik/PotsikCloudCapsule.kt` | the pill and the «Текст экрана снят при вызове Потса» note |
| `composeApp/src/commonMain/.../ui/settings/AssistantSettings.kt` | the settings card and the button that opens the system's default-apps screen |
| `composeApp/src/commonTest/.../assistant/AssistScreenTextTest.kt` | the pure rules, including the cut marks and the empty cases |

