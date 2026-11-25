# ADB Agent

**Last verified:** 2026-11-25

Pots can drive Android devices in the local network over ADB: run shell
commands, install and uninstall APKs. It works without root, without a bundled
`adb` binary and without a computer — the pairing handshake (SPAKE2 + TLS) is
implemented in the app itself (`libadb-android`).

Two surfaces exist, and they are deliberately separate:

- **Settings → ADB devices** (Android only) — add, pair, trust and remove
  devices. This is the only place a pairing code is entered.
- **Agent tools** — six `adb_*` tools the model may call on devices the owner
  has already added. Off by default.

## Turning it on

1. Enable wireless debugging on the target device and open
   «Pair device with pairing code» — the dialog shows a six-digit code, an
   address and a *pairing* port.
2. In Pots: Settings → ADB devices → «Добавить устройство». Enter the connect
   address (`host:port` from the wireless-debugging screen), the pairing port
   from the code dialog, and the six-digit code. «Сопряжение и подключение»
   pairs, marks the device **trusted** and connects.
3. Enable the master switch in Settings → Tools → **ADB devices**. The agent
   now sees the six tools.

For a TV, a Fire TV, or an Android 10 device without «Wireless debugging» there
is no pairing at all: connect it once over USB from a computer and run
`adb tcpip 5555`. After that the device answers at `host:5555` until it reboots;
add it in Pots by `host:5555` and press «Подключить». Old OS versions that
cannot do this are not supported — there is no fallback path.

## The six tools

| Tool | What it does | Confirmation |
| --- | --- | --- |
| `adb_list_devices` | Known devices, address, trusted flag, last seen. No network scan. | — |
| `adb_connect` | Connect by `device_id` or add a new `host:port` (private LAN only). | Adding a new address |
| `adb_disconnect` | Close the connection; the device stays in the list. | — |
| `adb_shell` | One shell command, 30 s deadline, last 8 KB of output. | Any non-read-only command |
| `adb_install` | APK from an http(s) URL (downloaded to the app cache, pushed, `pm install -r`) or a path already on the device. | Always |
| `adb_uninstall` | `pm uninstall <package>`; the package id is validated before a socket opens. | Always |

There is no pairing tool, no screenshot tool and no port forwarding in 3.21.

## Limits, stated honestly

- **`adb_shell` runs as uid 2000 (`shell`), not root.** Root-only paths and
  some `dumpsys` sections are unavailable; the tool description says so and the
  agent is told not to promise them.
- **Local network only.** `adb_connect` refuses any address outside 10/8,
  172.16/12, 192.168/16, fc00::/7 and loopback; there is no internet relay.
- **Foreground only.** There is no foreground service for ADB: sessions live
  while the app process lives, and nothing runs in the background.
- **Untrusted devices are read-only.** A device added by `host:port` without
  pairing accepts only commands that pass the read-only allowlist
  (`getprop`, `dumpsys`, `ls`, `cat`, `ps`, `id`, `pm list`, `settings get`,
  `date`, `logcat -d`). Anything else is refused before a byte reaches the
  device.
- **Output is cut to the last 8 KB**, with an explicit marker; the model is told
  what it is missing.
- **The mDNS search is not the agent's.** «Найти в сети» asks for the local
  network permission (Android 17+), and it only shows daemons this phone can
  see over mDNS; another device on the LAN is added by `host:port`.
- **Switching the master toggle off drops every open session** — the tools
  disappear from the agent's set on the next request.
- **The confirmation dialog is not wired into the chat yet.** `AdbGate`
  publishes the request and waits up to 120 s, but no chat screen collects it in
  3.21, so a gated action currently ends with «denied by user». Wiring one dialog
  for both Vault and ADB is a separate task; the gate itself (window, masking,
  headless refusal) is already in place and tested.

## Security notes

- The ADB host key lives in `filesDir/adb/` and is excluded from Android Auto
  Backup and device-to-device transfer: a key that leaves the device in a backup
  is a key an attacker can inherit.
- The pairing code and the key are never printed in a chat reply; the
  confirmation dialog masks credential-looking values (`token=…`,
  `password: …`) in the command it shows.
- Headless runs (heartbeat, scheduled task) cannot confirm anything, so every
  gated action fails there by design.

## Acceptance checklist (owner, on a real device)

1. Pair over loopback or LAN: Settings → ADB devices → pairing port + code →
   «Сопряжение и подключение». The device appears as trusted.
2. Enable the master switch, then ask the agent: «выполни `adb_shell id` на
   устройстве». The output must say `uid=2000(shell)` — not root.
3. Install an APK from a URL, then uninstall it by package id. Both actions ask
   for confirmation first.
4. On the target device press «Revoke USB debugging authorizations» (or forget
   the Pots key), then connect again: the app must offer pairing again
   («Устройство просит сопряжение заново»), not report «нет связи».
5. Turn the screen off and on, then repeat step 2: the session must survive a
   screen cycle and re-connect after a device sleep.
6. Turn the master switch off while a session is open: the next `adb_*` call
   must fail because the tools are gone.
