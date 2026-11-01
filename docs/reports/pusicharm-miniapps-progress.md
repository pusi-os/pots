# PusiCharm and mini-app progress

**Date:** 2026-11-01

## Delivered

- Android companion voice bridge, BLE pairing and appearance synchronization have been developed alongside the PusiCharm firmware. The user confirmed the flashed firmware works and long messages append in chunks.
- The empty chat offers a HandsFree entry on supported platforms. Pots Build is available from the sandbox and its settings.
- Editing a saved mini-app creates a separate draft with copied records, schema and assets. The authoring instructions use the actual per-app loopback API.
- Android release APK 3.10.7 (194) was published to the dashboard with signing verification and installed on the connected phone. Its SHA-256 is `ec2974d7b577cffe84e443eae0bc95f7917ac58ac8b585f871047ad8b7266547`.
- Desktop tests and the desktop distributable build passed. Firmware commit `0572d15` passed its GitHub Actions build.

## Outstanding

- The firmware uses a finite bubble buffer and line limit; arbitrarily long messages are not guaranteed to fit in one bubble. BOOT interruption, reconnect and appearance paths still need full device testing.
- Offline speech models were deferred to the next phase. Offline agent answers additionally require a local LLM.
- Mini-app local actions exist, but automatic conversion of arbitrary agent callbacks into a fully autonomous application is not implemented or verified. Do not treat an arbitrary saved UI as offline-ready.
- macOS, Windows and iOS releases and end-to-end mini-app flows have not been verified in this session.
