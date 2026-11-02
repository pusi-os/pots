# PusiCharm and mini-app progress

**Date:** 2026-11-02

## Delivered

- Android companion voice bridge, BLE pairing and appearance synchronization have been developed alongside the PusiCharm firmware. The user confirmed the flashed firmware works and long messages append in chunks.
- The empty chat offers a HandsFree entry on supported platforms. Pots Build is available from the sandbox and its settings.
- Editing a saved mini-app creates a separate draft with copied records, schema and assets. The authoring instructions use the actual per-app loopback API.
- Android release APK 3.10.7 (194) was published to the dashboard with signing verification and installed on the connected phone. Its SHA-256 is `ec2974d7b577cffe84e443eae0bc95f7917ac58ac8b585f871047ad8b7266547`.
- Desktop tests and the desktop distributable build passed. Firmware commit `0572d15` passed its GitHub Actions build.
- The mini-app save path now computes offline readiness from persisted UI actions. Local store, navigation, toggle, clipboard, URL and supported host actions are accepted; callbacks, unknown actions, invalid UI and agent automations are not marked standalone-ready.
- The native runner keeps local actions connected to the app store and shows a safe fallback for agent actions. The web runner now reports unavailable platform servers instead of opening a dead localhost URL.
- The mini-app library and glass toolbar were checked for complete CRUD flow, version history, export/import, pinning and new-app access. App shortcuts have vector controls and accessible names.
- Desktop tests, Android debug compilation, screenshot tests and the Wasm browser distribution passed after the mini-app changes.
- Branding audit of desktop, web and Android source paths found no remaining `Kai` product references. External URLs found are intentional project, integration or license links.

## Outstanding

- The firmware uses a finite bubble buffer and line limit; arbitrarily long messages are not guaranteed to fit in one bubble. BOOT interruption, reconnect and appearance paths still need full device testing.
- Offline speech models were deferred to the next phase. Offline agent answers additionally require a local LLM.
- Arbitrary agent callbacks are intentionally not converted automatically; they are reported as agent-dependent and remain available through the edit/agent flow.
- Physical-device verification was not performed because no device was connected. Android, desktop and browser distributions were compile/test checked; iOS, macOS and Windows release packaging still require their respective host environments.
