# Pots / PotsCharm companion pairing report

Last updated: 2026-11-01

## Current state
- Pairing over GATT works end-to-end (code shown on the cube, typed in the app,
  bond stored). Verified on hardware: `confirm full=... rx=...` match, `paired`.
- The GATT link now stays alive on the **main screen** (it used to die the moment
  you left the CoPhone screen). The cube auto-reconnects + re-authenticates from
  the main screen, including after a reboot. Verified: `linked`, `auth ok`.
- Dialog is started from the main screen's SCAN button. The CoPhone screen is now
  pairing-only (old CALL/HISTORY/SETTINGS buttons removed, single BACK).
- After a fresh pairing the cube shows "связан" ~5 s and returns to the main
  screen on its own (BOOT also leaves, since BACK is the only stop).

## Root cause #1 — pairing HMAC mismatch (fixed)
The GATT handshake offered the cube's ephemeral public key `s_pephPub` in
`OP_PAIR_HELLO`, but derived the shared secret in `OP_PAIR_REPLY` from `s_priv`,
which was never assigned (static zeros). Cube and phone therefore derived
different `pairKey`s: the 6-digit SAS matched only because the person typed the
cube's digits, while the confirm HMAC (keyed by `pairKey`) mismatched and the
bind never landed.
- Fix: derive with `s_pephPriv` (the scalar behind the offered public key), drop
  the dead `s_priv`, and ignore duplicate `OP_PAIR_REPLY` after the code is up
  (the scalar is wiped after the first derive). `src/cophone_ble.cpp`.

## Root cause #2 — link dropped when leaving the CoPhone screen (fixed)
`copPhoneLeave()` called `CoPhoneBle::end()`, which disconnected, and
`CoPhoneBle::tick()` ran only on the CoPhone screen. So the main screen's start
button saw `connected()==false` and said "connect the phone".
- Fix: leaving the screen stops only an active dialog, never the link; the
  transport pump + radio-quiet now run on the main screen too while a bond
  exists; radio quiet follows the link state, not the screen
  (`syncCophoneRadio()`). `src/main.cpp`.

## Root cause #3 — bond not loaded after reboot (fixed)
`CoPhoneBle::begin()` (which loads the NVS bond) ran only when the CoPhone
screen opened, so after a reboot the main-screen pump never saw the bond and
could not redial. Fix: lazy one-time `begin()` on the first main-screen frame.

## UI changes
- CoPhone screen bottom bar: only BACK is drawn/hit (pairing-only screen).
  Physical cursor stops reduce to BACK, so BOOT cancels pairing or leaves.
- Main-screen start button says "ПОДКЛЮЧАЮСЬ…" while a bonded link is redialing
  instead of the dead-end "ПОДКЛЮЧИ ТЕЛЕФОН".

## Builds / uploads this session
- App: Pots 3.9.5 (182) published to dashboard 222 (pairing logic unchanged; the
  pairing fix is firmware-side).
- Firmware: `pio run -e xiaozhi-cube-154` + flash to `/dev/ttyACM0` — OK, several
  iterations.

## Remaining work — voice bridge (the big one)
The app's `CoPhoneGatt.Listener.onAudioUp` / `onTextUp` are still no-ops, so no
audio actually flows yet. Needed to talk to the agent through the cube:
1. Decode Opus from AUDIO_UP (cube mic, 16 k/60 ms).
2. STT (Vosk offline or cloud `/audio/transcriptions`; pref `sttEngine`).
3. Feed the transcript into Pots' chat/LLM pipeline.
4. TTS the reply (system or cloud; pref `ttsEngine`), encode Opus 24 k/60 ms,
   stream back on AUDIO_DOWN, honoring `tts start/stop` TEXT frames.
5. Mirror the dialog bubbles into the Pots COMPANION channel.
`CoPhonePrefs` already carries the engine/model/voice/rate settings for this.
