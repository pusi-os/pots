# Built-in mini-app server and folders

**Last verified:** 2026-10-25

The built-in server turns a saved mini-app into something a phone browser can
open: static files from the app's own folder, plus a small same-origin JSON API
for records, files, notes and the calendar. It is off until the user turns it on
in **Settings → Integrations → Built-in mini-app server**.

## What it is for

A native mini-app is a `pots-ui` tree rendered by the app. A **web mini-app** is
a folder the agent filled with `index.html`, CSS, JS, images and scripts; the
server serves that folder to the browser, and the app keeps working without the
LLM. Both kinds live in the same library and the same storage.

## Folder layout

Every app is a folder under the mini-apps root (Android: the app's external
files dir, so it is visible over USB/MTP; desktop: `~/.pots/mini-apps/`; iOS:
Documents):

```
mini-apps/<slug>-<short-id>/
  app.json          the whole app: screens, schema, metadata, version list
  data.json         a portable dump of the app's records
  versions/         one JSON snapshot per version
  README.md         human-readable summary
  web/              HTML, CSS, JS and assets for a web app
  user/             files and notes the app may read/write (shared user area)
  .drafts/<id>/     an app the agent is still building; moved on Save
```

The SQLite database remains the live source of truth for records; `data.json` is
the folder's portable copy, written on every save. A web app's own files are not
touched by the metadata writes.

## Enabling and port

The section in Integrations is split into a server card and a database card:

- **Run the built-in server** — start it with the app;
- **Keep it running in the background** — on Android, a foreground service
  (`MiniAppServerService`) holds the process so the browser can still reach it;
- **Port** — default `8099`, `1024–65535`; the field uses the full width and
  changing it restarts the server;
- **Restart**;
- a status surface with a running/stopped dot, the current URL, live counters
  (uptime, requests, bytes served, errors) and **Open in browser**;
- a database browser: pick an app, pick a collection, inspect records, add a
  JSON record, delete one, or clear the collection. Pickers and editors use the
  full card width, and long records scroll inside the card.

The server binds `127.0.0.1` only. Nothing on the local network can reach it.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | A small index of saved apps |
| GET | `/api/health` | `ok`, port, uptime, counters |
| GET | `/api/apps` | Saved apps as JSON |
| GET | `/apps/<appId>/<path>` | Static file from the app folder; a missing path falls back to `entryPoint`/`web/index.html` |
| GET | `/api/apps/<appId>/manifest` | The app document |
| GET | `/api/apps/<appId>/collections` | Collections with field lists and counts |
| GET | `/api/apps/<appId>/records/<collection>` | Records |
| POST/PUT | `/api/apps/<appId>/records/<collection>` | Add/replace a record (JSON object body; `?recordId=`) |
| DELETE | `/api/apps/<appId>/records/<collection>` | Delete one (`?recordId=`) or clear |
| GET | `/api/host/info` | Which host services exist |
| GET/POST/DELETE | `/api/host/files` | List, write and delete files under `mini-apps/user/` |
| GET/POST | `/api/host/notes` | Notes as Markdown files under `mini-apps/user/notes/` |
| GET/POST | `/api/host/calendar` | List and create calendar events (Android, permission required) |

The API is same-origin: requests carrying an `Origin`/`Referer` from another host
are refused. Static pages get a CSP that allows inline code and localhost
fetches, because that is what a self-contained web app needs.

## Native apps and host calls

A native `pots-ui` app can use the same host services without a browser through
the `host_call` action:

```json
{"type":"host_call","service":"calendar.list","params":{"from":"2026-01-01T00:00:00Z"},"storeTo":"events"}
```

The result is written into the named collection and shown by a `records` node.
Supported services: `calendar.list`, `calendar.create`, `files.list`,
`files.read`, `files.write`, `files.delete`, `notes.list`, `notes.write`. A list
service clears `storeTo` before writing the new rows. This path never calls a
model.

## Behaviour and limitations

- The server is loopback-only; there is no LAN mode and no authentication
  beyond the loopback binding and same-origin check.
- Web apps must be saved before they have a URL; drafts are private folders.
- Calendar access needs the Android calendar permission already granted. The
  server cannot raise a permission dialog from a background service; a denied
  calendar call returns an empty list / `null`.
- Files and notes are text; binary files can be stored in the app folder and
  served, but the settings record editor edits JSON records only.
- The per-app database is the SQLite store keyed by app id; the folder's
  `data.json` is a portable dump, refreshed on save, not a second live engine.
- The server is JVM/Android only. iOS and web keep native mini-apps and folder
  storage but do not start an HTTP listener; the Integrations section reports it
  as unavailable there.

## Key Files

| File | Purpose |
| --- | --- |
| `composeApp/.../data/miniapp/MiniAppServer.kt` | Server contract, state, host bridge and counters |
| `composeApp/src/jvmShared/.../MiniAppServer.jvm.kt` | NanoHTTPD implementation, routes and static serving |
| `composeApp/.../androidMain/.../MiniAppServerService.kt` | Android foreground service for background mode |
| `composeApp/.../androidMain/.../MiniAppHost.android.kt` | Calendar host bridge |
| `composeApp/.../ui/settings/MiniAppServerSection.kt` | Integrations section, counters and database CRUD |
| `composeApp/.../ui/settings/MiniAppServerViewModel.kt` | Server/port/background actions and record editing |
| `composeApp/.../data/miniapp/MiniAppRepository.kt` | Folder files, drafts, export/import and entry point |
| `composeApp/.../ui/miniapp/MiniAppRunner.kt` | Opens a web app in the browser; renders native apps |
| `composeApp/.../ui/miniapp/MiniAppSession.kt` | Native `host_call` execution |
