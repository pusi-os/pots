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
   - Web: static assets served by MiniAppServer with a service worker + IndexedDB cache.

2. Declare collections first.
   - Define collections and records before wiring UI. Keep record shapes stable; add fields additively.

3. Use host actions only.
   - In saved apps use store_put, store_delete, navigate, host_call. Never embed LLM-dependent callbacks; convert any prototype callback to a host_call or store_* action during save.

4. Web app file layout.
   - index.html, app.js, styles.css, sw.js, manifest.webmanifest. Keep paths relative. Serve from same origin as the host API.

5. Inject snippets.
   - Host API client, IndexedDB wrapper, service worker, form-submit handler, offline fallback. See snippets below.

6. Sandbox testing.
   - Use execute_shell_command (node/python) to lint/test assets, validate JSON, run sqlite queries, generate icons. Document failures before saving.

7. Save readiness checklist.
   - [ ] No prototype-only/LLM callbacks remain.
   - [ ] Collections and records persisted.
   - [ ] Web assets written and valid.
   - [ ] Automations registered.
   - [ ] Version marked standalone-ready.
   - [ ] Offline fallback present.

## Snippets

### Host API client
```js
const host = {
  async call(method, path, body) {
    const r = await fetch(path, { method, headers: { "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined, credentials: "same-origin" });
    if (!r.ok) throw new Error(r.status + " " + r.statusText);
    return r.json();
  },
  storePut(collection, record) { return this.call("POST", "/api/store/" + collection, record); },
  storeDelete(collection, id) { return this.call("DELETE", "/api/store/" + collection + "/" + id); },
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

### Service worker
```js
const CACHE = "app-shell-v1";
const SHELL = ["/", "/index.html", "/app.js", "/styles.css", "/manifest.webmanifest"];
self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
});
self.addEventListener("fetch", (e) => {
  if (e.request.method !== "GET") return;
  e.respondWith(caches.match(e.request).then((r) => r || fetch(e.request).then((res) => {
    const copy = res.clone();
    caches.open(CACHE).then((c) => c.put(e.request, copy));
    return res;
  }).catch(() => caches.match("/index.html"))));
});
```

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
const result = await host.call("POST", "/api/host", { tool: "list_records", args: { collection: "items" } });
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
