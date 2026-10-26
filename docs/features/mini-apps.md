# Mini-apps (the Interactive UI studio)

**Last verified:** 2026-10-24

Interactive UI mode is also a small app studio. The agent designs the screens; the
user saves the result as a **mini-app** with its own persistent data, version
history and toolbar icon. Saved apps run on their own — no chat scroll, no
network call for the local parts — and can be edited, exported, imported and
shared.

## Concepts

### Saving a mini-app

In Interactive UI the top bar has three studio controls to the left of the close
button: **New**, **Save** and **My mini-apps**. New starts a clean interactive
session. Save gathers every distinct `pots-ui` screen the session produced, the
data collections the agent declared, the records written while building, and the
source conversation, then stores them as one app. The last screen is the one the
app opens on; earlier screens stay available as tabs in the runner.

The first save asks for a name, a description and an emoji icon; later saves of
the same app append a new version without asking again.

### Where an app lives

The app **is its folder** — `mini-apps/<slug>-<id>/` — with `app.json`,
`data.json`, `versions/`, `README.md`, and every file the app uses: `web/` for a
web app (HTML, CSS, JS, images, scripts), `user/` for files and notes the app
reads and writes. Drafts live under `mini-apps/.drafts/<id>/` and are moved into
the app folder by Save. On Android the root is the app's external files
directory (visible over USB/MTP); on desktop it is `~/.pots/mini-apps/`; on iOS
it is Documents.

SQLite is the live index and record store: one row per app, one row per version,
one row per record keyed `(app, collection, record)`. The folder's `data.json` is
the portable dump written on every save; the live per-app slice of SQLite is what
the settings database browser edits. The **version tree** is append-only: edits,
restores and re-saves all add nodes.

Files can be binary (images, fonts, WASM) as well as text. The export bundle
carries them as base64, so an app shared with another Pots install arrives with
its web pages, scripts and graphics intact.

### Web apps

An app whose manifest says `kind: web` is served from its folder and opened in
the device browser. The agent builds it with `mini_app_data`'s file actions
(`write_file`, `write_file_base64`, `list_files`, `read_file`, `delete_file`) and
`set_entry` (`entry_point: "web/index.html"`). The browser app talks to the same
records, files, notes and calendar through the [built-in server](mini-app-server.md).
Web apps do not use the LLM unless their own JavaScript explicitly calls one.

Native apps can reach the same host services without the browser through the
`host_call` action (`calendar.*`, `files.*`, `notes.*`), which writes the result
into a collection for a `records` node to display.

### The data model

The agent designs the app's data with the `mini_app_data` tool. It can declare a
collection and its fields, insert/query/delete/clear records, inspect existing
collections, declare automations and record capabilities. Fields are advisory —
the store keeps each record as a JSON document — which keeps it cheap for small
models to use and still gives the UI a schema to render.

The UI reaches the same store through two `pots-ui` additions:

- the `records` node renders a collection, with an optional per-record action;
- `store_put` and `store_delete` actions write and delete without an AI
  round-trip; `navigate` switches to another saved screen.

While the user is still building, records and schemas are written under the
current conversation id. Save moves them onto the app, so the first save is not
a copy step the user has to think about.

### The library

**My mini-apps** is a sheet, reachable from the interactive top bar and from the
chat's glass toolbar. It lists saved apps and provides:

- **Open** — run the app;
- **Open in editor** — seed a fresh interactive session with the app's final
  screen, schema and records so the agent can change it;
- **Edit** — change the name and description (also a versioned save);
- **Version history** — the tree, with a Restore button on every node;
- **Export** — one `.json` bundle containing the app, its records and its
  history;
- **Import** — pick such a bundle; a colliding id gets a new one, so importing
  your own export adds a copy rather than overwriting the original;
- **Delete** — asks first, names the app, and removes the database rows, records
  and the storage folder.

The star on each row pins the app to the chat toolbar.

### The chat toolbar

The button to the right of the chat's top bar opens a glass strip under it. With
no saved apps the strip is a single plus that starts the studio. With apps it
shows the pinned ones as icons; tapping one opens it, and the tune chip reopens
the library to change the selection. The selection is remembered across restarts.

### Running an app

Saved apps open full-screen in the runner. It renders the latest screen with the
ordinary `pots-ui` renderer, with local actions wired to the app's store. It has
a back/close button, the app's icon and name, a screen tab strip when there is
more than one screen, and an automation card for apps that declared any.

Actions that need the agent — a plain `callback`, or an automation's **Run** —
leave the runner and open a fresh interactive session seeded with the app, its
schema, its current records and the request. The agent's reply becomes a new
screen that can be saved as the next version.

Automations with a cron expression are registered as ordinary scheduled tasks
when the app is saved (and re-registered on every save, replacing the app's
previous tasks). They therefore ride the existing scheduling switch, heartbeat
conversation and daemon.

### Capabilities

Apps can declare what they intend to use: calendar, notes, files, notifications,
scheduling. The declaration is a manifest fact used by the editor prompt and the
UI. Actual access still goes through the agent and the same tools and runtime
permissions as the main chat — an app cannot grant itself anything the app does
not already have.

## Behaviour

- Saving needs at least one rendered `pots-ui` screen; otherwise the app says so
  and nothing is written.
- A new screen is added to the app's screen list only when it differs from the
  ones already there, so re-saving an unchanged session does not duplicate tabs.
- `store_put`, `store_delete` and `navigate` work offline in a saved app; a
  `callback` needs the agent.
- The runner has no chat input of its own; **Edit** is how the user asks for a
  change, which keeps one conversation per app edit and makes the version tree
  readable.
- Exports are self-contained JSON and contain no credentials; imported records
  keep their ids inside the new app, and imported files are written back into the
  app folder.
- A web app is launched through the built-in server, so the server has to be
  running (it is started on demand when the user picks the app). Native apps do
  not need the server unless they use `host_call`.

## Limitations

- The data store is a document store on SQLite, not an arbitrary SQL engine:
  collections are named sets of JSON records, not tables with joins.
- Automations run through the chat agent; an app cannot execute its own compiled
  backend. A cron task's prompt is what the agent receives, so it should carry
  the app id and say what to do with the records.
- Capabilities are declarative. A capability the host app does not expose (for
  example a calendar on a platform without calendar tools) is inert.
- Web has no SQLite and no filesystem: apps still save, version and run from
  settings storage, but the readable folder is absent.
- Restoring an old version creates a new version; there is no destructive
  rollback.

## Key Files

| File | Purpose |
|------|---------|
| `composeApp/.../data/miniapp/MiniApp.kt` | App, screen, collection, record, bundle, version and summary models |
| `composeApp/.../data/miniapp/MiniAppPersistence.kt` | SQLite-backed store and the settings fallback for web |
| `composeApp/.../data/miniapp/MiniAppRepository.kt` | Save/version/delete/export/import, records, toolbar selection, storage folder |
| `composeApp/src/commonMain/sqldelight/.../miniapp.sq` | The three tables and their queries; `1.sqm` is the migration |
| `composeApp/.../ui/miniapp/MiniAppsSheet.kt` | Library, CRUD, version tree, export/import |
| `composeApp/.../ui/miniapp/MiniAppsGlassToolbar.kt` | The chat's glass strip of pinned app icons |
| `composeApp/.../ui/miniapp/MiniAppRunner.kt` | Full-screen saved-app runner |
| `composeApp/.../ui/miniapp/MiniAppSession.kt` | Local action engine: records, store writes, screen navigation |
| `composeApp/.../ui/dynamicui/MiniAppRuntime.kt` | The renderer's runtime seam and `LocalMiniAppRuntime` |
| `composeApp/.../ui/dynamicui/PotsUiNode.kt` | `RecordsNode` |
| `composeApp/.../ui/dynamicui/UiAction.kt` | `StorePutAction`, `StoreDeleteAction`, `NavigateAction` |
| `composeApp/.../tools/MiniAppDbTool.kt` | The agent's `mini_app_data` tool |
| `composeApp/.../ui/chat/ChatScreen.kt` | Studio top bar, save dialog, library, runner branch, glass toolbar |
| `composeApp/.../ui/chat/ChatViewModel.kt` | Draft/save/edit flows, automation registration, mini-app seeding |
| `composeApp/.../data/ChatSystemPromptBuilder.kt` | Persistent-data instructions and the new component/action catalog |
