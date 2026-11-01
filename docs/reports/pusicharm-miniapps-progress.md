# PusiCharm and mini-app progress

**Date:** 2026-11-01

## Delivered

- Android companion voice bridge, BLE pairing and appearance synchronization have been developed alongside the PusiCharm firmware. A previous device session confirmed a dialogue; the current build has not been fully exercised end to end.
- The empty chat offers a HandsFree entry on supported platforms. Pots Build is available from the sandbox and its settings.
- Editing a saved mini-app creates a separate draft with copied records, schema and assets. The authoring instructions use the actual per-app loopback API.
- Android release APK 3.10.4 (190+1) was published to the dashboard and installed on the connected phone. Its SHA-256 is `b87a8a71e5de5b2ee60dbe6660d377d1107c426eb17eb93bc0b9972b3603bfed`.
- Desktop tests and the firmware build passed; screenshot generation passed before the latest mini-app changes.

## Outstanding

- Long PusiCharm messages still exceed the bubble buffer and rendering limit. The latest BOOT, reconnect and appearance paths need device testing.
- The Russian Vosk model and Piper Irina are not bundled; voice synthesis uses the system engine. Model distribution and licensing require resolution. Offline agent answers additionally require a local LLM.
- Mini-app local actions exist, but automatic conversion of arbitrary agent callbacks into a fully autonomous application is not implemented or verified. Do not treat an arbitrary saved UI as offline-ready.
- macOS, Windows and iOS releases and end-to-end mini-app flows have not been verified in this session.
