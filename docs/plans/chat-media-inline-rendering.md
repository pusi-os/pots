# Plan: inline media in chat (images, video, audio)

Status: planned, not implemented.
Last verified: 2026-10-28

## Goal

Render media produced by the agent and by tools inline in chat messages, with a full-size lightbox, zoom/pan for images, and seekable media players for video/audio, including playlists and the character dance animation while audio plays.

## Scope

- Images: open any image in chat in a full-screen viewer with pinch-to-zoom, pan, double-tap zoom, and save to gallery.
- Video: inline player with seek bar, pause/resume, fullscreen and lightbox for both file outputs and direct video links.
- Audio: seek bar, pause/resume, playlist support with next/previous and a track list, plus an optional fullscreen/player sheet.
- Character animation: trigger the existing `dance` animation of Потсик while audio is playing and stop it on pause/end/error.

## Missing details to specify before coding

- How to distinguish a media URL/path from prose (markdown images/videos/audio, raw links, tool attachments).
- Size limits and lazy loading for remote media, thumbnail placeholders, and HTTP Range support.
- Download/cache policy and cleanup.
- Save permissions / SAF / storage access on Android, desktop, web.
- Fullscreen and picture-in-picture scope.
- Subtitle/captions support for video if available.
- Playback continuation when the message scrolls out of view.
- Behaviour in HandsFree mode (must not steal focus indefinitely).
- Fallback when a platform player cannot open a container.

## Proposed implementation

1. Extend `MarkdownRenderer` with media nodes instead of only text.
2. Add `ChatMediaAttachment` metadata (`url`, `mime`, `localPath`, `size`, `duration`).
3. Add a common `Lightbox` component:
   - image viewer with `Zoomable` state
   - video viewer
   - download/save action
4. Android actuals:
   - save to MediaStore with `RELATIVE_PATH Pictures/Pots`
   - share intent for external export
   - ExoPlayer/MediaPlayer for video/audio
5. Desktop:
   - use `java.awt.Desktop` browse for external save
   - VLC/JavaFX-free simple players or platform HTML5 widget
   - save via existing `saveFileToDevice`
6. Web/wasm:
   - HTML5 `<video>` / `<audio>` through Compose interop
   - download attribute and CORS awareness
7. Character integration:
   - observe audio playback state in `ChatScreen` and call the Potsik dance animation action
   - ensure only one concurrent dancer state

## Key files

- `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/chat/ChatScreen.kt`
- `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/markdown/MarkdownRenderer.kt`
- `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/potsik/PotsikManager.kt`
- platform `Platform.*` actuals for open/save/media picking
- `docs/features/chat.md` and `docs/features/potsiki.md`

## Phases

1. Image lightbox + zoom + save.
2. Inline video player with seek/pause/fullscreen.
3. Inline audio player + playlist.
4. Character dance integration.
5. Validation tests and screenshot updates.
