# Linux Sandbox

**Last verified:** 2026-11-19

Pots ships a self-contained Linux environment on Android so the assistant — and the user, via the in-app Terminal — can run real shell commands. The agent can install packages, write and run scripts, hit the network, and reach external servers over SSH/SFTP/FTP. The sandbox runs the user-space `proot` runtime against a rootfs extracted into the app's private storage; no root or system access is required.

The sandbox is **Android-only**. iOS, desktop, and web have no-op stubs — sandbox operations are simply unavailable on those platforms.

The sandbox toolbar offers Terminal, Files and Packages, followed by an action opening Pots Build. This action is also available before the chat sandbox is installed. Once Linux is installed, the Linux Sandbox settings card also offers an action to open Pots Build and return to settings afterwards.

## Concepts

### Choosing a distribution

The Settings card offers two, and the choice is always open — before the first install and at any point afterwards:

- **Debian 12** — the recommended default. ~150 MB, apt, glibc, and the same Linux [Pots Build](pots-build.md) runs in.
- **Alpine Linux** — the small option. ~4 MB, apk, musl. Pots Build cannot use it, so picking Alpine means Pots Build installs a Debian of its own alongside.

**Switching is not a reinstall.** Each distribution keeps its own install in its own directory, so picking the other one points the shell integration at it and leaves the one being left exactly as it was — same `/root`, same SSH keys, same skills, same installed packages. Switching back is instant. If the distribution picked has no install yet, the card simply offers to install it, and the other one still stays put. Nothing is ever downloaded or deleted by the act of switching; removing a Linux is only ever the explicit **Uninstall** action, which removes the one currently selected.

Because Debian has exactly one directory on the device, pointing the shell integration at Debian when Pots Build already set one up costs nothing at all — it is the install that is already there.

The picker lists which distributions are already on disk, so a choice that is a switch reads differently from one that is a download. It is hidden only while an install is actually running.

Whichever install is selected, its recorded distribution wins over the setting everywhere downstream — package commands, the tool descriptions sent to the model, the name on the card and in the Terminal header. Live shell sessions do not survive a switch (each is a `proot` bound to the outgoing rootfs); they are dropped and started again lazily against the new one, the same way they are after an uninstall.

Alpine is capped at 3.22 because 3.23+ ships apk-tools 3, which is incompatible with proot (`execveat`), so package installs would fail. Debian comes from the Linux Containers image index, architecture-matched, and needs proot's hardlink-to-symlink emulation because dpkg unpacks packages using hardlinks that Android refuses inside the app sandbox. Debian's apt is also configured to stay root rather than switch to its `_apt` user, which work profiles and system clones refuse.

**Sandboxes installed before this choice existed are Alpine, and open on Alpine.** Such a sandbox never recorded a choice, so the setting's default would otherwise send it to a Debian it has never had; an install already sitting in the chat sandbox's own directory counts as the choice until the user makes a different one. They are recognised on sight, keep their `/root`, their SSH keys and their skills, and are never re-downloaded — and switching to Debian and back finds them exactly as they were left.

### Carrying files across

Switching distribution keeps both installs, but it does not merge them — the new one starts with its own empty `/root`. So whenever the distribution *not* selected still holds files in its home that the selected one does not, the card offers to copy them: how many files, how large, and a **Copy Files** action.

The copy is a **merge, and it is one-way**. Anything already in the selected install wins and is never overwritten, and the install the files come from is left completely untouched — it stays a working fallback until the user chooses to remove it. That makes the action safe to repeat, and makes its own disappearance the signal that it worked: once everything has been copied there is nothing left to offer, and the row goes away.

What comes across is the user's home — SSH keys and `known_hosts`, installed skills, scripts, and whatever the agent wrote — with file permissions preserved, because a private key that arrives world-readable is one openssh refuses to use. Symlinks come across as symlinks rather than being followed, since a rootfs contains link loops.

Four kinds of thing are deliberately left behind:

- **`projects`** — a bind mount both installs already share, so there is nothing to move.
- **Shell startup files** (`.bashrc`, `.profile`, and friends) — every rootfs ships its own, written for that distribution's layout.
- **`.cache`** — regenerable, and often larger than everything that matters.
- **Coding-agent directories** — the binaries there are compiled against the source distribution's libc and cannot run on the target, and Pots Build only ever runs in Debian anyway.

Packages are not migrated either, and cannot be: the two distributions have different package managers and different package names. The Settings **Install Packages** action covers the standard bundle on whichever system is selected.

The full move is therefore: switch to the distribution you want, install it if it is not there, **Copy Files**, then select the old one and **Uninstall** it. Nothing is destroyed until that last step, which is the only one that removes anything.

### One Linux, or two

Debian has one install on this device, so whenever the shell integration is pointed at Debian the chat sandbox and Pots Build are the **same install**. One download, one rootfs, one uninstall: setting up Linux from Settings means opening Pots Build lands straight on the project list, and installing from Pots Build's setup screen leaves Settings reporting the sandbox as ready. Uninstalling from either surface removes it for both, and both confirmation dialogs say so. Project folders survive it.

Pointing the shell integration at Alpine instead splits them: the chat sandbox runs in its Alpine, and Pots Build keeps (or bootstraps) its own Debian. Both exist at once, which is exactly what makes switching back and forth free.

Package installs are serialized across the two, because a shared rootfs means the Settings "Install Packages" action and a Pots Build agent install could otherwise reach `dpkg` at the same moment and both fail on its lock. Each surface reports its own progress: an agent install in Pots Build also streams its console output into the screen — the setup surface while it runs, and the project list if it fails — and leaves it there if it fails, so the reason is on screen rather than only in logcat — see [Install log](pots-build.md#install-log).

Pots Build itself is never re-pointed by a switch. Its Linux is wherever Debian lives, whether or not the chat sandbox is currently sharing it, so a running project terminal is unaffected by anything the Settings picker does.

### Per-conversation shell sessions

Each chat conversation gets its own long-running `bash` process. The agent's shell tool routes through the conversation's shell, so working directory, exported environment variables, and any in-shell state carry from one tool call to the next within that chat — the way they do in any normal terminal. State does **not** leak across chats: `cd /tmp` in conversation A leaves conversation B sitting wherever it was. The in-app Terminal tab also has its own dedicated scratch shell, separate from any chat.

`/root` and the rest of the rootfs are still shared on disk across all sessions, so files an agent writes in one chat are visible to every other chat and to the Terminal tab. Only live shell state (cwd, exports, background `&` jobs, ssh-agent connections) is per-session.

So `cd /tmp` followed by `pwd` in the same chat returns `/tmp`. The assistant does not need to chain `cd dir && command` unless it specifically wants the directory change to be one-shot.

A shell is created lazily on first use and lives for the duration of the app process. When a conversation is deleted, its shell is closed. Sandbox reset closes every live shell.

Closing a session ends everything it started, background `&` jobs included: proot runs with `--kill-on-exit`, so the guest tree does not outlive the proot that carries it and a server started in a shell is not left holding its port.

The bash *process* itself dies with the app — cwd and exported env do not survive. The visible *transcript* of the current chat's session is kept in memory with a hard cap of 500 lines per session, and persisted to the conversation (capped at roughly 10,000 characters of trimmed output — about a screen and a half of scrollback) so re-opening an old chat after a restart still shows the tail of what was on screen, even though running another command starts a fresh shell. Persistence is debounced (~500 ms) so rapid output bursts coalesce into a single write rather than thrashing storage.

### Terminal tab session picker

The in-app Terminal tab shows two chips at the top whenever a chat is open: **Session** for the current chat's shell, and **Temporary** for the user's scratch shell. **Session** sits first and is auto-selected when opening the terminal from a chat, so the user immediately sees what the agent is operating on; the visible terminal also scrolls to the most recent output (including any transcript restored from disk). **Temporary** drops to the standalone scratch shell that isn't tied to any chat — its transcript is in-memory only and clears when the app process dies. With no chat active, only the **Temporary** chip is shown (and the chip row collapses entirely when there's nothing to switch between).

The agent's shell tool, package-manager operations from the Packages tab, and the Terminal scratch session each route to a distinct shell. A long-running install in the Packages UI no longer blocks the chat tool from running.

### Pre-installed tooling

Packages split into two tiers, and each distribution names its own.

**Base** — installed as the last step of setup, with no separate action and no way to opt out. The install is not considered finished until they are in, so an interrupted setup can never present itself as ready. On Alpine that is just `bash`: every persistent shell session (the agent's shell tool, the Terminal tab, the Packages tab's own commands) is literally an `exec bash` process, so the sandbox cannot function without it. On Debian it is the wider set every coding project needs — bash, ca-certificates, curl, wget, git, nano, less, unzip, python3, tar and coreutils — because that install doubles as Pots Build's.

**Optional** — `jq`, `nodejs` (plus `npm` on Debian), a Python package installer, plus remote-server tooling: `openssh-client` (provides `ssh`/`scp`/`sftp`), `lftp` (FTP and FTPS) and `rsync`. Alpine adds `curl`, `wget`, `git` and `python3` here since they are not in its base. These install only when the user taps **Install Packages** in Settings — a deliberate, separate action, never automatic.

Anything beyond the two tiers is one install away via the Packages tab. The tab never offers to uninstall a base package: those rows simply have no uninstall action.

`~/.ssh` lives under `/root`, which is durable app storage either way, so SSH keys, `known_hosts`, and SSH config survive restarts.

### Where `/root` lives

New installs keep `/root` **on the rootfs**, in app-internal storage, which is the only place Android reliably allows executing a file — that is what lets coding agents installed under `~/.local/bin` actually run. Only `/root/projects` is bound in from external app files, so project code stays reachable over USB/MTP.

Files anywhere in the tree are still openable: `open_file` and the in-app browser hand them to other apps through `FileProvider`, which is configured for both storage areas. What this layout gives up is browsing `/root` directly from a desktop file manager.

Sandboxes installed before the two Linux stacks merged keep the old arrangement — the whole of `/root` bound in from external storage — because relocating a user's files behind their back is not worth the tidiness. Their file browser still shows `/root` in the right place; it is grafted onto the listing rather than read out of the rootfs.

### SSH host configuration

After the package install completes, Pots seeds `~/.ssh/config` with a `Host *` defaults block: server keepalive (`ServerAliveInterval 30`, `ServerAliveCountMax 3`) so long-lived ssh tunnels and sftp sessions don't get killed by NAT timeouts, and `StrictHostKeyChecking accept-new` so the first connection to a host writes its key into `known_hosts` automatically without a `yes/no` prompt this shell can't answer (subsequent connections still reject *changed* keys — sane TOFU). The seeding step is idempotent and only writes if the defaults block is missing.

The agent has a dedicated **Configure SSH Host** tool that registers a named host alias (alias, hostname, optional user/port/identity file) by upserting a `Host` block into `~/.ssh/config`, optionally appending a line to `~/.ssh/known_hosts`. After registration, the agent drives ssh through the regular shell tool — `ssh myalias 'remote-cmd'`, `scp file myalias:`, etc. — with no flags. The tool never writes or uploads private keys; the user has to place those under `~/.ssh` separately. Repeated calls for the same alias replace the prior block in place, so configuration stays clean.

Password-only remotes are reachable too but openssh inside the sandbox can't answer interactive password prompts on its own (no PTY; ssh reads from `/dev/tty`, not stdin). The documented path is installing `sshpass` once (`apk add` or `apt-get install`, per the installed distribution), then invoke as `sshpass -p '<password>' ssh <alias>` (or `-f <password-file>` to keep the password off the command line). The host alias config still applies — sshpass only supplies the password and fakes a PTY internally. Both tool descriptions point the agent at this pattern so it surfaces during normal SSH workflows.

**No connection multiplexing.** openssh's ControlMaster feature is intentionally not enabled. The mux protocol creates its control socket via the `link()` syscall (atomic create-or-fail), and Android's kernel-level `protected_hardlinks` policy plus SELinux for the `untrusted_app` domain refuses `link()` from app processes regardless of file ownership or mode. `proot` can't translate around it because the check enforces against the real Android uid. The verified symptom when ControlMaster was tried: `muxserver_listen: link mux listener … Permission denied` on every ssh invocation. Each ssh call therefore does a full TCP+auth handshake; there is no held-connection optimization in this sandbox. The alias config alone still removes per-call flag repetition, which is the real ergonomics win.

### One-shot escape hatch

The assistant's shell tool accepts a `fresh: true` argument that runs the command in a brand-new short-lived `proot` instead of the persistent shell. State changes in that one-shot shell are discarded when it exits. The persistent session is the default; `fresh` is only there for the rare case where isolation matters.

### Background processes

`background: true` on the shell tool detaches the command into its own short-lived `proot` and returns a `session_id`. Background jobs do not share state with the persistent shell. The companion `manage_process` tool reports status, output, and lets the assistant kill them.

`kill` ends the process for real. The handle's `cancel()` runs first, then the manager waits up to five seconds for the OS to reap it, and only after that answers `success: true`. If the process is still alive at the deadline the answer is `success: false` and the session stays killable — a "terminated" that leaves the port taken is worse than an honest failure. `remove` follows the same rule: a session with a live process is terminated first, and it is only dropped from the table once the process is gone.

Ending the *guest* is the part that matters, because `proot` does not namespace pids: a guest child is an ordinary host process, and killing only `proot` leaves the command — and the port it opened — running as an orphan. So a background session records its own shell pid before running anything, by prefixing the command with `echo $$ > /tmp/<name>`; `/tmp` is bind-mounted from a host directory, so the host can read that pid back. `cancel()` reads it, kills the process tree under it deepest-first (a parent that is still alive can re-spawn what was just killed), then destroys `proot` itself.

Death is judged on the guest tree when its pid was recorded: every pid the walk killed has to be gone, not just `proot`. `proot` is the lighter process and dies first, and a `SIGKILL`ed child stays readable in `/proc` for a moment — believing "dead" on `proot` alone is what let a restart fail with the port still taken. Without a recorded pid (an older session, a PTY that died before writing one) only `proot` can be watched, so that path keeps the old, weaker guarantee.

The pid-file wiring in `ProotExecutor.executeStreaming` (the one line that hands the pid file to `startStreaming`) is not covered by a test — `ProotExecutor` and `ProotLauncher` live in `androidMain` and `proot` is not present on a build host. The primitives it calls (`guestPidCommand`, `killProcessTree`, `hostChildren`, `guestTreeIsDead`) are unit-tested; if that wiring changes, check it on a device.

The exit code a kill reports is the one the monitor published for that death; if none arrives within a moment, it is `-1`. A killed process has no exit status of its own, so `-1` there means "no code", not "failed" — `success` and `killed` carry that answer.

The session table (`BackgroundSessionRegistry`) is shared with desktop, so both platforms answer these calls identically; desktop kills the whole process tree too, because `sh -c` puts the real command in a child that would otherwise outlive the shell.

### Cancellation

Hitting **Cancel** in the Terminal — or any cancel signal coming from the chat — sends `SIGINT` to the running command (technically: every direct child of the persistent bash, delivered from a sibling proot, since Pots has no PTY to drive line discipline). If the process ignores `SIGINT`, the cancel escalates to `SIGTERM` then `SIGKILL`. If even that fails, the whole shell is reset; the next command transparently restarts a fresh bash. At most a single command loses session state.

The same applies when the agent's shell call is abandoned — the user stops the chat, or the tool's outer time limit (set a few seconds above the 60 s per-call maximum) runs out. The command is interrupted, or the shell reset, before the next call in that chat is allowed to start, so a stale command can never keep running in the session or leak its output into the next result.

### Self-healing

The shell session can break — the user types `exit`, a command crashes bash, the framing channel desyncs, or a per-call timeout expires with the shell still wedged. In every case the next command lazily starts a new shell. Working directory and exported env are lost in that one event; the system stays usable.

## Background mode

By default the sandbox lives inside the app process: when Android kills that process to reclaim memory, every `proot` and every bash dies with it. The **Work in the background** switch in Settings → Sandbox (Android only, offered once the sandbox is installed) keeps it alive instead, through a foreground service with a persistent notification that has its own **Stop** action. Stopping from the notification turns the switch off as well, so the card never claims a mode the user just ended.

The switch is the whole gate. Two things Android can take away are *reported*, never used to refuse the mode:

- **Battery-optimisation exemption** — `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the app declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which is what makes the request legal for a sideloaded build). Without it Doze can freeze or kill the app even with the switch on, so the card says so and offers both the dialog and the battery-settings list. The real state is `PowerManager.isIgnoringBatteryOptimizations(packageName)`.
- **Notifications** — `POST_NOTIFICATIONS` only decides whether the status notification is *visible*. The service starts without it, and the card says the status will be hidden rather than blocking the mode.

Both are re-read every time the screen resumes (`LifecycleResumeEffect`), not once at creation: an exemption revoked in system settings shows up as a warning on the next visit instead of a stale "all good".

### Foreground service types and their limits

| Type | API | Limit | Where used |
| --- | --- | --- | --- |
| `dataSync` | 29+ | **6 hours per 24** on Android 15+ (`onTimeout`), plus a 6-hour quota below that | `ModelDownloadService` — a model download really is a sync, and the cap is right for one |
| `specialUse` | 34+ | none | `SandboxBackgroundService`, `DaemonService`, `MiniAppServerService` — keep-alives for long-lived local work |
| `mediaProjection` | 29+ | tied to the projection session | `PotsikScreenCaptureService` (screen mode) |

`specialUse` arrived in API 34, so the declaration forks by version *inside* the service, not in the manifest: the manifest attribute is ignored below 34 (nothing reads it), and `startForeground` is called with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` only from 34 up and without a type below it. One service entry therefore covers every supported version, and old OSes keep working.

### OEM task killers

Some vendors kill background apps regardless of Android's rules. The card carries a short checklist as text rather than trying to defeat the system in code:

- **Xiaomi (MIUI/HyperOS)**: Settings → Apps → Pots → Autostart on; Battery saver → No restrictions.
- **Samsung (One UI)**: Settings → Battery → Background usage limits → remove Pots from *Sleeping apps*; App info → Battery → Unrestricted.

The **Autostart settings** button opens the vendor's own autostart screen when the ROM has one (MIUI's security centre, One UI's battery page) and falls back to this app's system page everywhere else.

### Reboots and process death

The service is `START_STICKY`, so a system kill during the day brings it back. A **reboot** does not: nothing restarts it at boot (Android 15 restricts which foreground service types may be launched from `BOOT_COMPLETED`, and `specialUse` is not one of them). The switch stays on in settings, and the next time the app is opened the controller re-applies it — so after a reboot the sandbox comes back when Pots is opened, not before. That is stated here rather than papered over with a boot receiver that the platform would refuse.

## Agent web panels

Pots Build starts the coding agents' own web UIs and hands the address to the
device's browser: `opencode serve` (4096), `pi-web` (30141) and Hermes' `webapp`
(9120). One row per installed agent sits in Pots Build's project list.

### Where each panel comes from

A panel is part of its agent's install, not a separate thing to set up. Pi's
installer runs `npm install -g @agegr/pi-web` with the Node runtime Pi already
needs, into that runtime's own prefix, so `pi-web` lands on the sandbox PATH next
to `pi`; Settings → Sandbox prints its version beside Pi's. Hermes' install ends
with `hermes webapp --build-only`, which builds the renderer bundle the panel
serves — a failure there leaves the agent installed and the row says the bundle
is missing rather than showing an empty page. OpenCode needs nothing: its web UI
ships inside the binary.

### Loopback, except Hermes on the LAN

proot creates no network namespace: the guest and the phone share one network
stack. A server bound to `127.0.0.1` inside the sandbox is therefore the phone's
own loopback, and Chrome opens `http://127.0.0.1:<port>` directly. OpenCode and
pi-web stay there.

Hermes does not, and that is the owner's decision: `webapp.host` is `0.0.0.0`
(`SandboxAgentConfig.WEBAPP_HOST`), so the panel is reachable from the owner's
other devices on the same network while the phone sits on a charger. Hermes gates
any non-loopback bind behind the dashboard sign-in — and a gated panel with no
account is not an open panel but one nobody can open — so Pots gives it one (see
*Passwords*).

The row shows the address other devices dial: `http://<lan-ip>:<port>`, with the
IP read from Android's network interfaces (`LanAddress`, site-local IPv4 only —
a CGNAT or VPN address is one the other devices cannot reach). With no network at
all the row falls back to the loopback address and says the panel is reachable on
this phone only.

That is also the honest caveat the card states once: while the sandbox runs, the
port is visible to every device on whatever network the phone is joined to, and
the password is what keeps them out. There is no TLS and no subnet filter — the
owner asked for access over the LAN, not for a fortress.

### Ports

| Panel | Preferred | Started with |
|---|---|---|
| OpenCode | 4096 | `opencode serve --port N --hostname 127.0.0.1` |
| Pi | 30141 | `pi-web --port N --hostname 127.0.0.1 --no-open` |
| Hermes | 9120 | `hermes webapp --no-open` |

A taken port moves the panel to the next free one (ten attempts, then an honest
"all busy" error), and the row shows the port it actually got. Hermes takes its
port from `webapp.port` in `~/.hermes/config.yaml`, so the panel replaces that
one block (`SandboxAgentConfig.withWebappPort`) before starting it, and reports
the port the printed link carries. The same write drops the bundled `basic`
dashboard-auth plugin from `plugins.disabled` when it is there
(`SandboxAgentConfig.withBasicAuthPluginEnabled`): Hermes' own
`ensure_basic_auth_plugin_enabled_in_config` does that for `hermes dashboard`, and
a gated panel whose provider is disabled opens on "sign-in unavailable". The
runtime reads the file back, and reports `Failed` with that reason if the plugin
is still off — a panel nobody can sign in to is not "running".

The probe itself runs under `/bin/bash`, not `/bin/sh`: it connects through
bash's `/dev/tcp`, which is not POSIX and which the image's `/bin/sh` (dash) does
not have — under dash the redirect fails, which would read as "nothing is
listening" and as "the port is free" at the same time. Only a refused connection
counts as free; a probe that did not run at all (bash missing, proot error,
timeout) is reported as taken, so a broken probe can never hand out a port
someone else is on.

### Passwords

OpenCode, pi-web and Hermes get a generated 24-character password on first start
(`OPENCODE_SERVER_PASSWORD`, `PI_WEB_PASSWORD`,
`HERMES_DASHBOARD_BASIC_AUTH_PASSWORD`), stored in Pots' settings and shown on
request — the row fetches it when the button is tapped, so a panel that never
started has none to show. It travels in the process environment only: not on the
command line (so it is not in `ps`), and not in the panel log. Hermes' login is
the constant `WebPanels.HERMES_USER` (`pots`) in
`HERMES_DASHBOARD_BASIC_AUTH_USERNAME`, and the pair goes into the launcher's
environment rather than into `~/.hermes/config.yaml` — Pots rewrites that file
from scratch on every agent start, and a plaintext password in it is worse than
none. `HERMES_DASHBOARD_BASIC_AUTH_SECRET` is deliberately left unset: Hermes then
generates a random per-process signing key (`_resolve_secret`), so a gateway
restart ends open sessions — one sign-in, in a panel that is a login form anyway —
and Pots carries one less secret.

Beside "Show password" the row has **Copy**, which is the way the password
normally leaves the app: it is fetched on the tap, put on the clipboard and never
rendered, so it works without revealing it. The clip is marked
`ClipDescription.EXTRA_IS_SENSITIVE`, which keeps it out of the system clipboard
history and replaces its contents in the paste preview (API 33+; older releases
ignore the flag). The revealed value is wrapped in a `SelectionContainer` as
well, so it can be selected by hand — the reason the copy button exists is that
a 24-character password had to be retyped into a browser window, and a second
route out costs nothing.

### How each panel signs a browser in

The three panels do not authenticate alike, and the row is built around that:

| Panel | What guards it | Password in the URL? |
|---|---|---|
| OpenCode | HTTP Basic on every request, HTML included | **Yes** — `http://opencode:<password>@127.0.0.1:4096/` opens already signed in; Chromium strips the userinfo from the address bar and from the history once the page has loaded |
| Pi (pi-web) | HTTP Basic on the API only; the HTML wants a session cookie | **No** — a password there is ignored for the page and left sitting in the address bar for nothing. The link points at `/login` instead |
| Hermes | the dashboard's sign-in form, with the account Pots passes in the launcher's environment | **No** — the form asks for it; credentials in the URL would be ignored there and left sitting in the address bar |

The address the row shows (`WebPanels.address`, or `WebPanels.hermesAddress` for
the panel that lives on the LAN) therefore stays credential-free:
it is rendered on screen, screenshotted and read aloud. The link handed to the
browser is minted on the tap by `WebPanelRuntime.link()` (`WebPanels.url`), which
reads the password and, for OpenCode only, puts it in the userinfo —
percent-encoded (`percentEncode`, the RFC 3986 unreserved set) because a raw `@`,
`:` or `/` would end the userinfo early and the browser would dial a host that
does not exist, leaving the panel unauthenticated with nothing saying why.

A password in a URL is what RFC 7230 §2.7.1 tells senders not to generate. It is
done here deliberately, and only for OpenCode: that panel listens on `127.0.0.1`,
which proot makes the phone's own loopback, so nothing on the LAN can see the
request; the password is generated per panel and used nowhere else; and the
alternative is retyping 24 characters on a phone keyboard. The two conditions
that keep it acceptable are that the URL is never logged — `openWebPanel` logs
the panel id, not the address, and the panel log never sees it either — and that
copying the password is always one tap away.

**Why pi-web has no auto-login.** Its HTML is not behind Basic auth at all: it
serves a form that trades the password for a session cookie lasting 30 days.
Credentials in the URL never reach that form, so the row says so in as many
words ("sign in once — the browser remembers it for 30 days") and offers the copy
button, rather than letting the owner wait for a login that is not coming. The
form is not filled in for them either: pi-web has a global rate limiter that
answers 429 and locks the panel for a minute after a failed attempt, so one
silent try that fails is worse than a visible form that always works.

### Hermes sits behind the sign-in form

`hermes webapp --no-open` prints one of two things. Behind the dashboard's
sign-in — which is where the non-loopback bind Pots writes puts it — it prints a
permanent address, and the row shows the LAN one with the login and password
underneath. Without the gate it prints a private link that works once and expires
in 60 seconds; the row then mints a fresh one on every tap and hands it straight
to the browser, because a cached link is spent by the first open, and says so
under the address.

Which one it is cannot be known from `--status`, so the runtime decides from what
the launcher printed at start and keeps that: a permanent address is never
re-minted, a one-use link never cached.

### Provider and model

There is no second configuration. pi-web reads `~/.pi/agent/models.json`, which
`SandboxAgentConfig.pi()` already writes; OpenCode's web UI is the same binary
reading the same `~/.config/opencode/opencode.json`; Hermes' webapp runs on the
same `~/.hermes/config.yaml` and `.env` as its CLI.

### Stop and autostart

A stop is a real `kill -TERM`, a wait, then `kill -KILL` — a server gets a chance
to close its port — and the panel only reports stopped once the port is free.
Hermes is the exception in the mechanics, not in the promise: its panel is the
gateway, so the stop is `hermes gateway stop`, followed by the same port check on
the address the row was showing.
The "Start with the sandbox" toggle brings the panel up when the sandbox becomes
ready, and a live pid with a listening port means nothing is started twice.

## Behavior

- **Tool availability follows install state**: the assistant's sandbox tools (shell command, `manage_process`, and the SSH-host config tool) are advertised to the model only when the sandbox is actually installed (Ready) *and* the sandbox toggle is on. Before the sandbox is installed they are not sent at all — there is no point offering tools that can only return "not installed," and the enable/disable toggle is itself hidden until install completes. Once installed, the switch on the distribution's card is the on/off control.
- **Switching distribution mid-life**: selecting the other distribution in the Settings card re-points the shell integration at that distribution's install without touching either one. When it is already there the card is showing the new system — its size, its packages, its Terminal — within a moment; when it is not, the card offers to install it and the previous one is still on disk, one tap away. The agent's sandbox tools follow: they are withdrawn while the selected distribution has no install and come back describing the new distribution's package manager once it does.
- **Copying files between the two**: the offer appears on the card only when the other install actually has something the selected one lacks, so it is a statement of fact rather than a standing button. The count and size are measured off the main thread and patched into the card when they land, never blocking the first paint or a switch. During the copy the card shows a running `n/total`, and afterwards the offer is gone because a second look finds nothing left.
- **First run**: Settings → Tools → Linux Sandbox picks a distribution and downloads its rootfs — a few MB for Alpine, around 150 MB for Debian, varying by architecture. After extraction the package index is refreshed (Alpine walks its mirror list rewriting `repositories` until one answers; Debian has a single index) and the base packages install automatically — no further action needed. The sandbox reaches Ready at that point, with the optional package set not yet installed; the Settings card shows a separate **Install Packages** button for those until the user taps it. The whole flow surfaces progress in the Settings sheet.
- **The status line is translated**: every word the card shows about the install — not installed, downloading, extracting, configuring, installing base packages, installing a named package, copying files with its running count, ready, and each way any of those can fail — is a translated string, in all the languages the app ships. The install itself runs outside the UI, so it reports which step it is on and the card is what puts that into words. Only the underlying error text an OS call or a download mirror hands back stays as it arrived, inside a translated sentence; when there is none, the sentence says so rather than trailing off. The same goes for the Terminal, whose "sandbox is not ready" and failed-command notices are translated too, while real command output is not.
- **State across the app**: each chat conversation has its own shell, and the Terminal tab has another. Files in `/root` and the rest of the rootfs are shared between them; live shell state (cwd, exports) is not. The Packages UI uses a separate "system" shell so its operations don't interfere with chats.
- **Network access**: outbound IP works (DNS is configured against `8.8.8.8` / `8.8.4.4`). SSH/SFTP/FTP/HTTP all work; the user's Wi-Fi/mobile-data permission applies as normal.
- **File visibility**: the whole filesystem, `/root` included, is the rootfs in app-internal storage; `/root/projects` and `/tmp` are bound in from elsewhere. Files the agent produces can be opened with `open_file` via Android's `FileProvider`, which is configured for both areas.
- **Tapping a file opens it here unless its name says otherwise**: the browser hands a file to another app only for the extensions nothing good comes of showing as text — images, video, audio, documents, archives, compiled binaries, databases, fonts. Everything else opens in the built-in editor, including files with no extension at all (`id_rsa`, `Makefile`, `known_hosts`) and extensions no list would ever cover (`.pub`, `.service`, `.rules`), which is most of what a Linux tree is made of. The editor then decides from the bytes rather than the name: valid UTF-8 becomes an editable buffer, anything else gets the "not text" card with its two ways out. Guessing "text" wrong costs one tap; guessing "binary" wrong used to mean the file could not be read in the app at all.
- **Open as text, on any file**: the row menu offers it next to "Open with app", so an extension the browser routes elsewhere — or one Android grabs — can still be read here. It goes through the same byte check, so a real binary lands on the "not text" card rather than pretending to be a document.
- **Handing a file to another app picks the right one**: whether the file is opened with the row menu or handed over by the agent, the app offered is chosen from the extension. Android's own extension table is corrected where it does not fit a Linux sandbox — most visibly for `.apk`, which it does not know at all, so tapping one now offers to install it (FOSS builds only; see [tools.md](tools.md)). Extensions Android has never heard of fall back to plain text when they look like source or config, which is most of what the sandbox produces.
- **Installed skills live here**: skills (see [skills.md](skills.md)) are stored as folders under `~/skills/<id>/` in the sandbox home — the sandbox is their single source of truth, which is why the Skills UI is Android-only and requires the sandbox to be installed first.
- **Tapping a sandbox-file link in chat**: when the model links to a sandbox file (e.g. a `file:///root/out.gif` markdown link), tapping it opens the file through the same `FileProvider` path rather than the platform URL handler. Handing a raw `file://` URI to Android's `startActivity` throws `FileUriExposedException`, so a global URI handler intercepts `file:` and absolute-path links and routes them to `open_file`; all other links (http/https/…) pass through unchanged.
- **Limits**: each shell call's stdout and stderr are individually capped at 15 000 characters; pipe through `head` / `tail` / `grep` for larger output. The default per-call timeout is 30 s and the maximum is 60 s.
- **Active-shell indicator**: each time a chat starts running a shell command via the agent's shell tool, the rounded background of the sandbox icon button in the chat top bar flashes once in the primary color (snap on, ~800ms fade out) so the user can see at a glance that the assistant kicked off a sandbox command.
- **Inline sandbox view**: the chat top-bar terminal icon toggles an inline sandbox surface (Android only) with sub-tabs — **Terminal** (interactive shell, default), **Files** (browse `/root`, open via FileProvider or a built-in text editor with Save), and **Packages** (search/install/uninstall/upgrade through the installed distribution's package manager; base packages cannot be uninstalled). Package search re-ranks hits so name matches come first: exact name, name prefix (typing `fast` surfaces `fastfetch` before packages that only mention "fast" in a description), then hyphen/underscore segment prefixes, then name contains, then description-only hits.
- **The file browser is shared with Pots Build**: the same browser — listing, breadcrumbs, editor, rename/delete, open-with — is pointed at whichever Linux environment the surface belongs to. Both browse their environment from the filesystem root and differ only in where they open: the home folder here, the open project in Pots Build. The two never share a position or an open file.
- **Bringing a file in from the phone**: the Files tab has an import action that copies a file picked from device storage into the directory currently on screen. Before this the only route into the sandbox was the agent's shell. The copy is streamed rather than buffered, so importing something large does not risk the app's memory, and an existing name is never overwritten — the incoming file is suffixed (`report-1.pdf`) instead. The action is offered only while the listing is showing, since it targets the visible directory; a busy indicator replaces it for the duration and the listing picks up the new file when it lands.
- **Why a file won't open in the editor**: the editor distinguishes the reasons rather than lumping them together. A file past the editor's size cap (512 KB) says so and reports its size, offering only to hand it to another app — a truncated buffer is not something that can be edited back safely. A file that is not valid UTF-8 can be force-opened, which decodes it with replacement characters for viewing and marks the buffer read-only; saving is withheld there, because writing those replacement characters back would destroy the file's real bytes. Anything the sandbox cannot read at all says that plainly. A NUL byte counts as "not text" before a decode is even attempted — it is legal UTF-8 and effectively never appears in real text.
- **The file browser refreshes itself**: the agent writes to the sandbox through the shell, so the browser cannot assume its listing is still current. Every time the Files tab becomes visible — selecting the sub-tab, opening the sandbox surface, or returning to the app from the background — the directory the user left off in is listed again, and re-entering a directory visited earlier lists it fresh rather than reusing what was shown before. Leaving the tab does not send the browser home: it stays where it was, and only the first open (or a surface that changed which folder it opens on) goes to the starting directory. The refresh is silent: entries stay on screen while it runs, and when the directory turns out to be unchanged nothing is redrawn, so the list never jumps or loses its scroll position. A file open in the built-in editor is also re-read, but only while the buffer has no unsaved edits — pending edits always win, which prevents a later Save from overwriting what the agent wrote.

## Limitations

- **No PTY → fullscreen TUIs do not work.** `vim`, `less`, `nano`, anything ncurses-based, `cbonsai` in animated mode, and any `ssh -t host fullscreen-cmd` will either refuse to start ("inappropriate ioctl for device" / "stdout is not a tty") or spam escape codes that don't render. Use the non-interactive variants: `cat`/redirected editors, `ssh user@host 'remote-cmd'` without `-t`. A proper PTY layer was prototyped and reverted — the build-out tradeoffs (terminal emulator complexity, IME interaction, scrollback) didn't pencil out for v1.
- **Process inspectors (`top`, `htop`) cannot see system-wide processes.** Android's `/proc` mount is `hidepid=2`, so `/proc/<pid>/` for processes owned by other UIDs is not visible. `proot` rewrites paths but can't bypass kernel UID enforcement. There is no fix without root. For workload monitoring inside the sandbox itself, use `ps`, `ps -p $$`, or `cat /proc/self/status`.
- **Subprocess stdout buffering.** `python3` / `node` / etc. fully buffer stdout when stdin is a pipe — output looks "stuck" until the buffer fills or the process exits. Use `python3 -u` or `stdbuf -o0 <cmd>` for interactive testing.
- **App backgrounding ends the session unless the background mode is on.** When Android kills the app process to reclaim memory, every `proot` (and therefore every bash) dies with it. With **Work in the background** on (see [Background mode](#background-mode)), a foreground service holds the process and the shells survive; without it the old behaviour stands: shells restart cleanly per conversation, but cwd, exported env, and any open SSH/SFTP connections are gone. The visible transcript of each chat's shell is persisted (trimmed tail) so the user still sees what was on screen, but live shell *state* is not. The mode is opt-in because it costs a permanent notification and, on many devices, a battery-optimisation exemption.
- **Memory cost of multiple sessions.** Each live shell is a `proot+bash` pair (tens of MB resident). Running many concurrent chats with shell-tool usage will accumulate sessions. There is no soft cap yet — closing a conversation drops its shell, sandbox reset drops them all.
- **Cancel without a PTY is best-effort.** A child that ignores `SIGINT`/`SIGTERM` forces a session reset; the user loses session state for that one command.
- **Stray output from backgrounded jobs** (`sleep 60 &` then "Done" later) can attach itself to whatever command is running when the kernel finally reports the exit. Matches normal terminal behavior.
- **iOS / desktop / web**: no sandbox. Stubs are no-ops — calls return empty results (or are simply unsupported) until those platforms get their own runtime.

## Key Files

| File | Purpose |
| --- | --- |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/sandbox/LinuxSandboxManager.kt` | Owns the sandbox's install marker (distro + where `/root` lives), the optional-package install, and the session-keyed map of live persistent shells. `selectDistro()` re-points it at the other distribution's install, dropping live shells and touching neither rootfs; `surveyMigration()` / `migrateHome()` are how files follow the user across. Seeds new per-chat shells from the conversation's persisted transcript and pipes transcript snapshots back to `ConversationStorage` after each command. `refreshInstallState()` is how it learns Pots Build installed the shared Debian. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/sandbox/SessionShell.kt` | Per-session facade over `PersistentSandboxShell`. Carries the live in-memory transcript, accepts an `initialLines` seed for restart restoration, and fires an `onChange` callback after each command so the manager can persist the tail. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/data/ConversationStorage.kt` | Conversation persistence. `updateShellTranscript(id, lines)` trims to ~10,000 chars total and writes the tail back into the conversation JSON. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/sandbox/PersistentSandboxShell.kt` | Long-lived bash, sentinel-based command framing, graduated `SIGINT`/`SIGTERM`/`SIGKILL` cancel, self-healing on shell death. One instance per session id. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/SandboxController.kt` | Common surface; `executeCommand{,Streaming}` take a `sessionId`. `SandboxSessions` defines the well-known ids: `DEFAULT`, `SYSTEM`, `TERMINAL`. `SandboxStatus.label` is the install step as a `SandboxStatusLabel` value for the UI to translate, `SandboxStatus.distro` carries the selected distribution to every UI and tool, `installedDistros` says which ones exist on disk, and `migration` says what the other one still holds. `selectDistro()` is the switch, `migrateHome()` the copy. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/sandbox/SandboxStatusText.kt` | Turns `SandboxStatus.label` into the sentence the card shows. The only place the status wording lives, so the Android install path reports steps rather than English. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/sandbox/SandboxPackagesViewModel.kt` / `SandboxPackagesScreen.kt` | Packages tab. Every command and every parser comes from the installed distro's `PackageManagerSpec`; the UI is the same either way. Gates uninstall against the distro's base packages. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/FileBrowserSource.kt` | The browsable-tree contract both Linux environments implement, so the Files UI is not tied to this sandbox. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/sandbox/SandboxFileBrowserViewModel.kt` | Files tab state: directory listing, navigation, the built-in text editor, and rename/delete. Owns the refresh-on-visible behavior — re-lists silently, holds the directory the user left off in, skips the state update when nothing changed so the list keeps its scroll, drops a listing that resolves after the user navigated away, and re-reads the open file only while its buffer is clean. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/sandbox/SandboxFileBrowserScreen.kt` | Files tab UI, rooted wherever the caller asks: breadcrumb path bar (which never offers a step above that root), keyed entry list, per-entry rename/delete/open menu, and the editor pane. Triggers the refresh whenever the browser resumes. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/SandboxUriHandler.kt` | `UriHandler` provided over `LocalUriHandler` in `App.kt`; routes `file:`/absolute-path links to `SandboxController.openFile`, delegates the rest to the platform handler. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/data/ConversationIdContext.kt` | `ConversationIdElement` coroutine-context element that threads the active conversation id from the chat layer down into tool execution without polluting `Tool.execute(args)`. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/sandbox/ProotExecutor.kt` | The chat sandbox's line-oriented view of a rootfs, over the shared `ProotLauncher`. Returns the map shape the shell tool and background jobs consume. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/linux/LinuxDistro.kt` | The two distributions, their base/optional/protected package sets, and which package manager each uses. Also the default (Debian) and what a marker-less install counts as (Alpine). |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/linux/PackageManagerSpec.kt`, `ApkPackageManager.kt`, `AptPackageManager.kt` | Commands and output parsers per package manager — install/remove/search/list/upgrade, plus what counts as an error and how many packages an upgrade replaced. Pure and unit-tested. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/linux/LinuxPaths.kt` | Storage layout for one install: rootfs, tmp, projects bind, proot and libtalloc, and the install marker (including adopting the legacy layouts). |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/linux/LinuxInstalls.kt` | Which of the two install directories holds a given distribution, or would hold it, and where each install's `/root` lives. The single answer both the chat sandbox and Pots Build resolve against, so a distribution has one home and switching never installs over the other one. |
| `composeApp/src/jvmShared/kotlin/com/pusios/pots/linux/HomeMigration.kt` | Surveys and merge-copies one install's home into another: what the destination is missing, what is never worth carrying (bind mounts, shell rc files, caches, agent binaries), and a copy that preserves permissions, keeps symlinks as symlinks, and never overwrites or modifies the source. Pure `java.io` and unit-tested. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/linux/LinuxInstaller.kt` | Download → extract → configure → base packages, for either distribution, cancellable and leaving no partial rootfs. Owns the process-wide package lock the two features share. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/linux/DistroSpec.kt` | Per-distribution facts: architecture names, rootfs URLs (Alpine mirror list, Debian LXC index), post-extract fixes, and the proot flags and environment each needs. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/linux/RootfsDownloader.kt` / `TarExtractor.kt` | One download path with mirror fallback, one tar reader handling both `.tar.gz` and `.tar.xz`. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/linux/ProotLauncher.kt` | proot argv, binds and environment, plus the shared `ProotHandle` and one-shot `execute`. Both this sandbox and Pots Build's PTY executor start their processes here. `cancel()` ends the guest recorded in the pid file, not just proot. |
| `composeApp/src/jvmShared/kotlin/com/pusios/pots/linux/GuestPath.kt` | Guest path → host file, following the binds, for both file browsers. Rejects traversal and anything resolving outside its root. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/build/WebPanels.kt` | The three agent web panels as declarations: preferred ports, the one command line each is started with, port choice, the password generator, the two URLs each panel has (`address` for the row, `url` for the browser — credentials for OpenCode only, `percentEncode` for the userinfo), and the parser for `hermes webapp`'s output (one-use link vs sign-in address). |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/build/WebPanelRuntime.kt` | Panel state machine, free of Android: start/stop/refresh/autostart over a `WebPanelGuest`, with the "start at most once", "report the port it got" and "stopped means the port is free" rules unit-tested against a fake guest. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/build/runtime/AndroidWebPanelGuest.kt` | The guest side of the above: shell calls and pid/port/log files under the bind-mounted `/tmp`, the `/dev/tcp` port probe, and the Hermes `webapp.port` rewrite. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/build/WebPanelsCard.kt` | The Pots Build rows: start/stop/retry, the tappable address that opens the device browser, the per-panel sign-in hint, the hidden-by-default password with its copy button and selectable value, and the autostart toggle. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/SandboxController.android.kt` | Routes `executeCommand` and `executeCommandStreaming` through the persistent shell; one-shot fallbacks live alongside. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/tools/ShellCommandTool.kt` | The `execute_shell_command` tool the assistant calls. Description, `fresh` flag, env/working-dir wrapping. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/tools/SshConfigureHostTool.kt` | The `ssh_configure_host` tool. Validates inputs, calls the config manager, returns an example invocation for the LLM. |
| `composeApp/src/jvmShared/kotlin/com/pusios/pots/sandbox/SshConfigManager.kt` | Pure-JVM writer for `~/.ssh/config` and `~/.ssh/known_hosts`. Owns the `# pots:<marker>:start/end` blocks (defaults + per-host) for idempotent upsert, file-mode lockdown, and the relative-to-`~/.ssh` identity-file resolution. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/tools/ProcessManager.kt` / `composeApp/src/jvmShared/kotlin/com/pusios/pots/tools/ProcessManagerTool.kt` | Background-job lifecycle: detached one-shot proot, status/kill controls. |
| `composeApp/src/jvmShared/kotlin/com/pusios/pots/tools/BackgroundSessions.kt` | The session table both platforms share: bounded output, the `BackgroundProcess` handle, and `kill`/`remove` that wait for the process to die before answering. |
| `composeApp/src/jvmShared/kotlin/com/pusios/pots/tools/ProcessTree.kt` | The guest-pid prologue a background command runs under, and the `/proc` tree walk that ends it deepest-first. Shared so it is testable off-device. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/SandboxBackgroundService.kt` | The background mode's foreground service (`specialUse`): persistent notification with a **Stop** action that also turns the switch off. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/SandboxBackgroundController.kt` | The mode's state and controller contract. `SandboxBackgroundState.working` is the toggle alone; `batteryWarning` and `notificationHidden` are statements, not gates. |
| `composeApp/src/androidMain/kotlin/com/pusios/pots/SandboxBackgroundController.android.kt` | Reads the live exemption and notification state, starts/stops the service, opens the battery and vendor-autostart screens. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/sandbox/SandboxTabsContent.kt` | The inline sandbox surface: Terminal / Files / Packages sub-tabs, the Session / Temporary chips, and the not-installed install prompt. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/sandbox/SandboxSessionViewModel.kt` | Terminal-tab ViewModel: line buffer, run/cancel state, stream draining. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/ui/settings/TerminalSheet.kt` | Visible terminal UI with command echo, color-coded streams, and an interactive input row. |
| `composeApp/src/commonMain/kotlin/com/pusios/pots/SandboxController.kt` (`NoOpSandboxController`) | The single no-op every non-Android target returns; iOS, desktop and wasm each supply only a one-line factory. |
