---
name: mini-app-authoring
description: Author robust standalone mini-apps for the Pots studio: data-driven web apps backed by the Integrations server, persisted via store_*/host_call, no LLM-dependent callbacks in saved apps. Use when creating, saving, or fixing mini-apps.
---

# Mini-app authoring

## Goal
Produce a saved mini-app that runs standalone: a last working version + Screen backed by its own backend, with all dynamic data flowing through host_call/store_put/store_delete/navigate. No callback may depend on LLM inference after save.

## Steps

1. Decide native vs web.
   - Native: pots-ui screens + collections + records, actions store_put/store_delete/navigate/host_call.
   - Web: static assets served by MiniAppServer; keep all assets local and use its per-app records API.

2. Declare collections first.
   - Define collections and records before wiring UI. Keep record shapes stable; add fields additively.

3. Use host actions only.
   - In saved apps use store_put, store_delete, navigate, host_call. Replace prototype callbacks with appropriate local actions while authoring; saving does not convert them automatically.

4. Web app file layout.
   - index.html, app.js, styles.css, sw.js, manifest.webmanifest. Keep paths relative. Serve from same origin as the host API.

5. Inject snippets.
   - Host API client and form-submit handler. See snippets below. The loopback server and SQLite work without internet.

6. Sandbox testing.
   - Use execute_shell_command (node/python) to lint/test assets, validate JSON, run sqlite queries, generate icons. Document failures before saving.

7. Save readiness checklist.
   - [ ] No prototype-only/LLM callbacks remain.
   - [ ] Collections and records persisted.
   - [ ] Web assets written and valid.
   - [ ] Automations registered.
   - [ ] The local actions have been tested without an LLM.
   - [ ] Offline fallback present.

8. Save and pin.
   - When the checklist passes, tell the user to press **Save** in the top
     toolbar of the interactive studio. The dialog shows what will be saved:
     screens plus the backend (collections, records, files). If the app has
     dynamic data, verify the backend counts are non-zero before saving; if
     they are zero, declare the missing collections/records first.
   - Offer the "add to the home mini-app panel" checkbox: it is on by default
     for new apps, so the app appears on the toolbar right away.
   - Saving a later version of the same app does not ask again; it appends a
     version and keeps the pin.

## Snippets

### Host API client
```js
const appId = location.pathname.split('/')[2];
const base = '/api/apps/' + encodeURIComponent(appId) + '/records/';
const host = {
  async call(method, path, body) {
    const r = await fetch(path, { method, headers: { "Content-Type": "application/json; charset=utf-8" }, body: body === undefined ? undefined : JSON.stringify(body), credentials: "same-origin" });
    if (!r.ok) throw new Error(r.status + " " + r.statusText);
    return r.json();
  },
  records(collection) { return this.call("GET", base + encodeURIComponent(collection)); },
  storePut(collection, record) { return this.call("POST", base + encodeURIComponent(collection), record); },
  storeDelete(collection, id) { return this.call("DELETE", base + encodeURIComponent(collection) + '?recordId=' + encodeURIComponent(id)); },
  navigate(to) { window.location.href = to; }
};
```

### IndexedDB wrapper
```js
function openDb(name, version, upgrade) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(name, version);
    req.onupgradeneeded = (e) => upgrade(req.result, e.oldVersion);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}
async function idbGet(db, store, key) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, "readonly");
    const r = tx.objectStore(store).get(key);
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}
```

### Service workers
The loopback server already serves local files without internet. If a service worker is needed, scope it to the app's own directory, use relative asset URLs, and never cache mutable `/api/` responses.

### Form submit -> store_put
```js
document.querySelector("#form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const data = Object.fromEntries(new FormData(e.target));
  await host.storePut("items", data);
  e.target.reset();
  await refresh();
});
```

### host_call usage
```js
const result = await host.records("items");
```

### Offline fallback
```js
window.addEventListener("offline", () => document.body.classList.add("offline"));
window.addEventListener("online", () => document.body.classList.remove("offline"));
```

## Sandbox usage
- Run `node --check app.js` to syntax-check.
- Validate JSON with `python -m json.tool manifest.webmanifest`.
- Query SQLite with `sqlite3` if needed.
- Generate icons with python/Pillow.

## Notes
- Keep migrations additive; never rename historical identifiers.
- ProGuard keep rules apply to host API client classes.
