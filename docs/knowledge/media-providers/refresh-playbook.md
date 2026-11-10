---
okf_version: "0.2"
---

# Refresh playbook — media providers

## Adding a new provider

1. Fetch first-party docs (base URL, auth header, catalog/price endpoint, generate + poll endpoints, file result access, error codes, billing header).
2. Create `docs/knowledge/media-providers/<provider>.md` with the same sections as `plusvibe.md` (base URLs, endpoints table, opts, file inputs, lifecycle, billing/idempotency, families, provider-specific footnotes) + `last_verified` date.
3. Add a row to `index.md` catalogs.
4. Create `composeApp/.../media/<Provider>MediaProvider.kt` implementing `MediaProvider` (`listModels`, `quotePrice`, `submitJob`, `pollJob`, `downloadResult`); map provider DTO conservatively (`ignoreUnknownKeys = true`, never fail whole catalog on one unknown model).
5. Register in DI and per-instance settings (same API key as chat instance by default; separate media key only if provider requires it).
6. Update `docs/features/media-studio.md` + `docs/features/multi-service.md` (provider row, key files) and this log.

## Refreshing an existing provider

1. Re-fetch docs pages + live `…/api/*-catalog` JSON (public where possible); diff against the bundle.
2. Update the bundle (dates, prices snapshot, removed/added `model:variant` ids, contract changes like retired sync endpoints).
3. Update the Kotlin provider **and** the bundle together — never one alone.
4. Run `./gradlew :composeApp:desktopTest` and `./gradlew spotlessApply`.

## Price snapshot rule

Never hardcode provider prices in Kotlin as truth. Show `fromRub` / per-variant `priceRub` from the live catalog (or offline bundled fallback with its date), and always quote a real `POST …/price` before showing a checkout total. Result footer shows server `priceRub` (`x-pv-cost-rub` equivalent).
