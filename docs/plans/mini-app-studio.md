# Mini-app studio — implementation plan

**Status:** implemented (see `docs/features/mini-apps.md`)
**Last verified:** 2026-10-24

This document is the working plan for turning Interactive UI mode into a small
app studio: the character joins the working tile, the top bar gains New / Save /
My apps, saved apps keep their data and version history, the main chat grows a
glass toolbar of app icons, and speech output reflects what hands-free is
actually doing.

## 1. Character in Interactive UI mode

- Add the same `PotsikAnchorKind.EMPTY` / `WORKING` anchors the chat uses.
- Draw one `PotsikOverlay` over the interactive surface.
- While a request runs the stage is in `PotsikPhase.WORKING`, which already
  drives the random working playlist in `PotsikEngine`; nothing new is needed
  for the animations themselves.

## 2. Top bar: New / Save / My apps

- New starts a fresh interactive session (history cleared, a new draft id).
- Save snapshots the final rendered pots-ui screens, the conversation, the
  declared data schema and the draft records into a mini-app, writes a folder
  snapshot to the user storage and appends a version to the app's tree.
- My apps opens a management sheet: open for editing, rename/description edit,
  delete with confirmation, version tree, export and import.

## 3. Main chat app toolbar

- A top-bar button toggles a glass panel under the bar.
- No saved apps → a single `+` that opens the studio.
- Saved apps → icons of the apps the user chose to pin; a configure list selects
  which ones appear.

## 4. Saved app runtime

- Apps open full-screen in a runner that renders their latest screen with
  `PotsUiRenderer`.
- Persistent data lives in SQLite (`mini_app_record`), exposed to the renderer
  through local actions and a `records` display node.
- Local actions write without an AI round-trip. Capability flags are declared in
  the manifest; automations carry a prompt and optional cron expression.

## 5. Speech output and hands-free

- Starting hands-free turns speech output on.
- Turning speech off while hands-free is active makes the loop listen without
  speaking; turning it back on mid-loop lets the next reply be spoken.
- The top-bar toggle always reflects the real state, in both modes.

## Storage model

- `mini_app` — one row per app, the JSON document is the source of truth for the
  manifest and screens.
- `mini_app_version` — append-only snapshots with `parent_version`, so history is
  a tree rather than a list of overwritten files.
- `mini_app_record` — schema-declared collections for app data. A portable JSON
  document store on top of SQLite: works on Android, desktop and iOS, and falls
  back to settings storage on wasm.
- Each save also writes `mini-apps/<slug>/` under the app files directory:
  `app.json`, `data.json`, `versions/`, so the user has a readable folder they
  can back up.
