package com.pusios.pots.build

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The coding agents Pots Build can install on a fresh Debian. Each one ships a
 * self-contained installer, so the base system plus curl is all they need.
 *
 * Install locations (guest paths under `/root`):
 * - OpenCode → `~/.opencode/bin/opencode`
 * - Pi → `~/.local/bin/pi`, a link into the managed install at `~/.pi/agent`
 * - Hermes → `~/.hermes/hermes-agent/.hermes/bin/hermes`, linked into `~/.local/bin/hermes`
 *
 * Proot injects all three bin dirs into PATH for probes/installers. Login shells
 * rebuild PATH from profile files, so Pots Build also writes a profile.d snippet
 * and launches agents by absolute path.
 *
 * Every agent is pointed at the provider Pots itself is using, by
 * [SandboxAgentConfig] — nobody should have to log each CLI in separately.
 */
@Immutable
data class BuildAgent(
    val id: String,
    /** Product name — shown as-is, not translated. */
    val title: String,
    val binary: String,
    val installCommand: String,
    /**
     * How long [installCommand] may run. Pi downloads one pinned release; Hermes
     * builds a Python tree and its command on the device itself, which on a phone
     * takes many times longer than a download.
     */
    val installTimeoutSeconds: Long = 900,
)

object BuildAgents {

    const val OPENCODE = "opencode"
    const val PI = "pi"
    const val HERMES = "hermes"

    /**
     * Every directory an agent CLI can land in, in the order PATH must carry them.
     * The one list both PATH builders start from: the login-shell profile snippet
     * Pots Build writes into the rootfs, and the chat terminal's startup export —
     * that shell runs with `--noprofile`, so it never reads the snippet.
     */
    const val AGENT_BIN_PATH = "/root/.local/bin:/root/.local/share/pi-node/bin:/root/.opencode/bin"

    /**
     * The agent dirs followed by whatever PATH the shell already has, so system
     * dirs are never dropped. A line of shell, executed as-is by the chat terminal
     * and written as-is into the profile snippet.
     */
    const val AGENT_PATH_EXPORT = "export PATH=\"$AGENT_BIN_PATH\${PATH:+:\$PATH}\""

    /** Contents of `/etc/profile.d/pots-build-path.sh` — what login shells read. */
    val AGENT_PATH_PROFILE: String =
        "# Managed by Pots Build — keep coding-agent CLIs on PATH for login shells.\n" +
            AGENT_PATH_EXPORT + "\n"

    /**
     * OpenCode's own installer, fetched from its repository rather than from
     * `https://opencode.ai/install`: that URL answers 307 through Cloudflare from
     * a sandbox, while the script behind it is a plain file on GitHub.
     *
     * The branch is `v2` on purpose. v2 is the current major — its installer
     * unpacks the npm package `@opencode/cli-<target>` into `$HOME/.opencode/bin`
     * (the same path v1 used, so nothing else here moves) and resolves the newest
     * version on its own, so nothing has to be pinned. The `v1` script refuses a
     * 2.x release outright: "This installer only installs OpenCode 1".
     *
     * `--no-modify-path` keeps the installer's hands off `.bashrc`/`.zshrc`: Pots
     * Build carries the bin dir on PATH itself, and a sandbox has no login shell
     * to read rc files.
     */
    private const val OPENCODE_INSTALL = """
        set -e
        mkdir -p /root/.opencode/bin
        curl -fsSL https://raw.githubusercontent.com/anomalyco/opencode/refs/heads/v2/install -o /tmp/pots-opencode-install.sh
        # stdin closed: no answer is coming from a sandbox if the installer asks.
        bash /tmp/pots-opencode-install.sh --no-modify-path < /dev/null
        rm -f /tmp/pots-opencode-install.sh
    """

    /**
     * Node.js is unpacked into the home rather than installed from apt, because
     * Pi needs 22.19+ and Debian bookworm's nodejs is 18. The version is resolved
     * from nodejs.org's own index for the running architecture, so this works on
     * every phone Debian runs on (arm64, armhf, x86).
     *
     * Then Pi's own installer, not npm: it resolves one pinned release with its
     * lock file and lays it down under `~/.pi/agent`, which is also what `pi
     * update` maintains. npm would pick up transitive ranges instead — Pi's own
     * README says so. Node has to be in place first, because the installer only
     * fetches Node itself when there is a terminal to ask on, and a sandbox
     * install has none.
     */
    private const val PI_INSTALL = """
        set -e
        case "${'$'}(uname -m)" in
          aarch64|arm64) node_arch=arm64 ;;
          armv7l|armv6l) node_arch=armv7l ;;
          x86_64|amd64) node_arch=x64 ;;
          i?86) node_arch=ia32 ;;
          *) echo "unsupported architecture: ${'$'}(uname -m)"; exit 1 ;;
        esac
        mkdir -p /root/.local/bin /root/.local/share/pi-node
        node_dist=https://nodejs.org/dist/latest-v22.x
        node_file=${'$'}(curl -fsSL "${'$'}node_dist/SHASUMS256.txt" | grep -o "node-v[0-9.]*-linux-${'$'}{node_arch}.tar.gz" | head -1)
        if [ -z "${'$'}node_file" ]; then echo "no Node.js build for ${'$'}node_arch"; exit 1; fi
        curl -fsSL "${'$'}node_dist/${'$'}node_file" -o /tmp/pots-node.tar.gz
        tar -xzf /tmp/pots-node.tar.gz -C /root/.local/share/pi-node --strip-components=1
        rm -f /tmp/pots-node.tar.gz
        export PATH="/root/.local/share/pi-node/bin:/root/.local/bin:${'$'}PATH"
        # stdin closed: the installer's own prompts (start Pi now?, install Node?)
        # must not wait for an answer nobody is there to give.
        curl -fsSL https://pi.dev/install.sh -o /tmp/pots-pi-install.sh
        sh /tmp/pots-pi-install.sh < /dev/null
        rm -f /tmp/pots-pi-install.sh
        # Pi's web panel. Installed with the same Node runtime Pi already needs, into
        # that runtime's own prefix, so `pi-web` lands in the bin dir already on the
        # sandbox PATH — and not in a second copy of Node. `latest` on purpose: the
        # panel only gained --hostname/--no-open/PI_WEB_PASSWORD after 0.7.9. The
        # prefix is named rather than inferred from the node binary's location, so a
        # stray npmrc cannot install it somewhere PATH does not carry.
        /root/.local/share/pi-node/bin/npm install -g --prefix /root/.local/share/pi-node --no-audit --no-fund @agegr/pi-web
    """

    /**
     * Hermes from its own repository, through the installer its developers ship:
     * the checkout carries `scripts/install.sh`, which pins uv and Python, then
     * builds the `hermes` command into `~/.hermes/hermes-agent/.hermes/bin`.
     * PyPI is not an option — its only release is an old build without `webapp`.
     *
     * The clone is the only part done here: the installer is idempotent and
     * updates an existing checkout itself, so a rerun is safe.
     *
     * **uv must copy, and the only lever left is the uv binary itself.** Inside
     * proot on a phone, uv hardlinks its cache into the venv and the guest refuses
     * (`failed to hardlink file from …: Operation not permitted`). Both paths in
     * that message carry a `.l2s.` prefix: that is proot's `--link2symlink`
     * emulation (`l2s` = link-to-symlink), which had already turned an earlier
     * hardlink into a symlink, and hardlinking *that* artifact on is what answers
     * `EPERM`. So the remedy is neither a proot flag nor a cache wipe — uv has to
     * copy.
     *
     * Nothing uv reads as configuration reaches the stage that fails: `hermes pm`
     * strips every `UV_*` variable (`pm/environment.py`, `_base_environment` keeps
     * index settings only), points `XDG_CONFIG_HOME` at a temporary directory, and
     * builds its own runtime (`installs/<id>/pm-runtime`) with `no_config=True`,
     * i.e. `uv --no-config`. The `uv.toml` below therefore only covers the stages
     * inside the checkout; environment and config file stay because they are free,
     * not because they fix anything.
     *
     * What is left is argv, and pm builds it from an absolute path: uv is called as
     * `<store>/uv-<version>-<target>/uv` (pm's own store entry, `pm/paths.py`),
     * never as `uv` by name from PATH. So the wrapper belongs in that entry — the
     * real binary moves to `uv.real`, a `sh` wrapper takes its name. Two traps in
     * it were checked against uv 0.13.5 and both matter:
     * - `--link-mode` is **not** a global flag: `uv --link-mode=copy cache prune`
     *   answers `unexpected argument`, and pm makes that call. Flags cannot be
     *   prepended to `"$@"`, so the wrapper injects them after the subcommand, and
     *   only for `venv`, `sync` and `pip install|sync` — the calls that link files
     *   and accept the flag. Only `cache prune`, `cache clean`, `python find` and
     *   `pip check` reject it (`lock` and `export` do accept it, which is why the
     *   wrapper is an allowlist: a deny-list would have to be right about every
     *   other subcommand).
     * - pm re-hashes its tool closure on every explicit install (`ensure(...,
     *   verify=True)`) and publishes a pristine uv over the entry when the digest
     *   does not match — silently undoing the wrapper a moment before the stage it
     *   was meant to fix. The wrapper step therefore rewrites the recorded digest
     *   of that entry through pm's own `pm.store.tree_digest`, so the check passes
     *   and the wrapped binary survives. That rewrite writes `facts.json` the way
     *   pm itself does — `pm.filesystem.durable_write_bytes`, read as `utf-8-sig`
     *   — because a plain rewrite truncates first, and an install killed in that
     *   window would leave pm a file it cannot parse.
     *
     * A first-ever install needs one more thing: uv does not exist until the
     * installer downloads it, i.e. after the wrapper step has run, and pm then
     * installs its own copy. The command retries the installer once, wrapping
     * again in between; the retry is cheap because the clone, uv and Python are
     * already there. [BuildAgent.installTimeoutSeconds] covers both attempts.
     *
     * One more piece is not about uv at all: `cache/uv` and `site-packages` still
     * hold `.l2s.` artifacts from the attempts that failed, and a retry can trip
     * over them before the wrapper ever runs. A one-time marker wipes those two
     * directories, but only when the scan actually finds a `.l2s.` artifact (or
     * cannot scan at all): a working install keeps its `pm-runtime` instead of
     * rebuilding it for nothing, and a device with nothing built is left for the
     * next run to judge.
     *
     * The link probe in the log keeps this diagnosis honest on the next device: it
     * prints the device ids of the two directories uv links between and attempts
     * the cross-directory link itself. The probe this replaced linked two names
     * inside `/root`, which succeeds even where the real link is refused, so it
     * reported `ok` while proving nothing. The probe runs *before* the one-time
     * cleanup, because that cleanup deletes the directories it reports on.
     *
     * - **`--channel main`.** The default stable channel makes the installer
     *   fetch a release tag's entire history into the shallow clone — 643k
     *   objects, over 45 minutes on a phone — and then keep the existing
     *   checkout anyway. `main` follows the branch and skips that fetch.
     */
    private const val HERMES_INSTALL = """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        # Harmless where it arrives (the installer's own uv calls read it) and
        # the only lever if a future pm forwards it to its uv children.
        export UV_LINK_MODE=copy
        export UV_NO_CACHE=1
        mkdir -p /root/.hermes /root/.local/bin
        # The hermes-agent commit this app's web panel was built from, written by the
        # app before this install. Empty on a build that ships no bundle (a
        # developer build), and then nothing below pins anything.
        POTS_WEBAPP_PIN=''
        if [ -f /root/.hermes/pots-webapp-pin ]; then
          POTS_WEBAPP_PIN=${'$'}(tr -d '[:space:]' < /root/.hermes/pots-webapp-pin)
        fi
        # `tar -xJ` runs xz, which a Debian base does not carry on its own; a rootfs
        # set up before xz-utils joined the base list gets it here.
        apt-get install -y --no-install-recommends xz-utils >/dev/null 2>&1 \
          || echo "pots: xz-utils is missing; the panel cannot unpack its renderer"
        if [ ! -d /root/.hermes/hermes-agent/.git ]; then
          git clone --depth 1 https://github.com/NousResearch/hermes-agent.git /root/.hermes/hermes-agent
        fi
        printf 'link-mode = "copy"\nno-cache = true\n' > /root/.hermes/hermes-agent/uv.toml
        grep -qxF uv.toml /root/.hermes/hermes-agent/.git/info/exclude 2>/dev/null \
          || echo uv.toml >> /root/.hermes/hermes-agent/.git/info/exclude
        # Evidence first: the one-time cleanup below deletes the very directories
        # whose device ids and .l2s. artifacts this probe reports on. The whole
        # block runs with `set +e` — a probe that aborts the install because one
        # of its inputs is missing would be worse than no probe at all.
        # The store pm and install.sh both resolve: HERMES_RUNTIME_DIR when it is
        # set, ~/.hermes/tools otherwise.
        POTS_UV_STORE=${'$'}{HERMES_RUNTIME_DIR:-/root/.hermes/tools}
        echo "pots: link probe"
        ( set +e
          GEN=${'$'}(ls -d /root/.hermes/installs/*/pm-runtime/generations/*/ 2>/dev/null | head -1)
          CNT=${'$'}(ls -d /root/.hermes/cache/uv 2>/dev/null | head -1)
          if [ -n "${'$'}GEN" ] && [ -n "${'$'}CNT" ]; then
            # Device id decides everything: two filesystems cannot be hardlinked
            # at all, and then no flag and no cleanup can help — uv has to copy.
            stat -c '%d %n' "${'$'}GEN" "${'$'}CNT" 2>&1
            # The link that fails runs BETWEEN those two directories. The probe
            # this replaced linked two names inside /root, which works even where
            # the cross-directory link is refused — it proved nothing while
            # looking ok.
            echo a > "${'$'}GEN/pots-x"
            ln -f "${'$'}GEN/pots-x" "${'$'}CNT/pots-x" 2>&1 && echo "  cross-dir ln: ok" || echo "  cross-dir ln: FAILED"
            python3 -c "import os;os.link('${'$'}GEN/pots-x','${'$'}CNT/pots-y')" 2>&1 && echo "  cross-dir os.link: ok" || echo "  cross-dir os.link: FAILED"
            rm -f "${'$'}GEN/pots-x" "${'$'}CNT/pots-x" "${'$'}CNT/pots-y"
            # proot's link2symlink leaves .l2s. artifacts behind; hardlinking one
            # of those is what answers EPERM, so a count above zero is the poison.
            # site-packages sits inside the generation, not beside it: the
            # generation path already ends in "generations/<hash>/".
            ls -a "${'$'}GEN"lib/python3*/site-packages/packaging 2>/dev/null | grep -c l2s
          else
            echo "  nothing built yet: no pm-runtime or uv cache to link between"
          fi
          # How the installer and pm reach uv: by name from PATH, or by the absolute
          # path of pm's own store entry (the one the wrapper has to occupy).
          command -v uv
          ls -d "${'$'}POTS_UV_STORE"/uv-*/uv 2>/dev/null
          grep -n 'native(self.uv)' /root/.hermes/hermes-agent/pm/environment.py 2>/dev/null | head -2
        ) || true
        # The failed attempts left .l2s. artifacts in the cache and in
        # site-packages, and a retry can trip over them before the wrapper below
        # ever runs. The wipe is earned by the evidence, not by the calendar: only
        # a real .l2s. artifact justifies deleting a runtime, so a working install
        # keeps it and a device with nothing built has nothing to delete. The
        # marker is set once the question has been answered, so the scan costs one
        # install — except on a device with nothing built, where it stays unset so
        # that poison appearing later is still caught.
        POTS_UV_MARK=/root/.hermes/.pots-uv-copy-mode
        if [ ! -f "${'$'}POTS_UV_MARK" ]; then
          POTS_L2S=''
          for POTS_DIR in /root/.hermes/installs /root/.hermes/cache; do
            [ -d "${'$'}POTS_DIR" ] || continue
            # A scan that cannot run counts as evidence: "could not look" must
            # never be read as "clean".
            if POTS_L2S=${'$'}(find "${'$'}POTS_DIR" -name '.l2s.*' -print -quit 2>/dev/null); then
              if [ -n "${'$'}POTS_L2S" ]; then break; fi
            else
              POTS_L2S=unknown
              break
            fi
          done
          if [ -n "${'$'}POTS_L2S" ]; then
            rm -rf /root/.hermes/installs/*/pm-runtime /root/.hermes/cache/uv
            : > "${'$'}POTS_UV_MARK"
            if [ "${'$'}POTS_L2S" = unknown ]; then
              echo "pots: could not scan for .l2s. artifacts; cleared pm-runtime and the uv cache once"
            else
              echo "pots: .l2s. artifacts present; cleared pm-runtime and the uv cache once"
            fi
          elif [ -x /root/.hermes/hermes-agent/.hermes/bin/hermes ]; then
            : > "${'$'}POTS_UV_MARK"
            echo "pots: no .l2s. artifacts; leaving the existing runtime alone"
          else
            echo "pots: nothing built yet; no stale .l2s. artifacts to clear"
          fi
        fi
        # pm strips every UV_* variable and builds its runtime with --no-config, so
        # neither the environment nor uv.toml reaches the stage that fails. It also
        # calls uv by the absolute path of its store entry, never by name from PATH:
        # that entry is the only place a lever can sit. The real binary moves to
        # uv.real and a wrapper takes its name. Idempotent by the marker line — a
        # second run must never copy the wrapper onto itself.
        pots_wrap_uv() {
          for f in "${'$'}POTS_UV_STORE"/uv-*/uv; do
            [ -f "${'$'}f" ] || continue
            if grep -q pots-uv-wrapper "${'$'}f" 2>/dev/null; then
              echo "pots: uv wrapper already installed (${'$'}f)"
              continue
            fi
            cp -f "${'$'}f" "${'$'}f.real"
            # Every line below sits at the raw string's own indent so trimIndent()
            # hands the script to sh with column-0 heredoc terminators and a
            # column-0 shebang; the nested indents are the script's, not Kotlin's.
            cat > "${'$'}f" <<'POTS_UV_WRAPPER'
        #!/bin/sh
        # pots-uv-wrapper — installed by Pots Build, never by pm.
        # pm builds its own runtime with --no-config and strips every UV_* variable,
        # so the command line is the only thing left to change. --link-mode is NOT a
        # global uv flag (uv --link-mode=copy cache prune answers "unexpected
        # argument"): it goes after the subcommand, and only for the subcommands that
        # link files and accept it.
        real=${'$'}(dirname "${'$'}0")/uv.real
        sub=${'$'}1
        case "${'$'}sub" in
          venv|sync)
            shift
            exec "${'$'}real" "${'$'}sub" --link-mode=copy --no-cache "${'$'}@"
            ;;
          pip)
            second=${'$'}2
            case "${'$'}second" in
              install|sync)
                shift 2
                exec "${'$'}real" pip "${'$'}second" --link-mode=copy --no-cache "${'$'}@"
                ;;
            esac
            ;;
        esac
        exec "${'$'}real" "${'$'}@"
        POTS_UV_WRAPPER
            chmod +x "${'$'}f"
            echo "pots: uv wrapper installed (${'$'}f)"
            # pm re-hashes its tool closure on every explicit install and would
            # publish a pristine uv straight over the wrapper. Recording the digest
            # of what the entry holds now is what lets that check pass.
            PYTHONDONTWRITEBYTECODE=1 python3 - "${'$'}(dirname "${'$'}f")" <<'POTS_UV_DIGEST' || echo "pots: uv digest not refreshed (pm may reinstall uv)"
        import json
        import pathlib
        import sys

        sys.path.insert(0, "/root/.hermes/hermes-agent")
        from pm.filesystem import durable_write_bytes
        from pm.store import tree_digest

        entry = pathlib.Path(sys.argv[1])
        facts = entry.parent / "facts.json"
        if not facts.is_file():
            sys.exit(0)
        # The same two calls pm makes on this file: it reads it as utf-8-sig (a BOM
        # left by another tool would otherwise break json.loads on an invisible
        # \ufeff), and it only ever writes it through durable_write_bytes, which
        # fsyncs and replaces it atomically. A plain write_text truncates first, so
        # killing the install here would leave a half file that pm cannot read.
        data = json.loads(facts.read_text(encoding="utf-8-sig"))
        row = data.get("packages", {}).get("uv")
        if not isinstance(row, dict) or row.get("entry") != entry.name:
            sys.exit(0)
        row["digest"] = tree_digest(entry)
        durable_write_bytes(facts, (json.dumps(data, indent=2, sort_keys=True) + "\n").encode("utf-8"))
        print("pots: uv digest refreshed (" + entry.name + ")")
        POTS_UV_DIGEST
          done
        }
        pots_install_hermes() {
          # stdin closed: no answer is coming from a sandbox if the installer asks.
          bash /root/.hermes/hermes-agent/scripts/install.sh --non-interactive --skip-browser --skip-computer-use --channel main < /dev/null
        }
        pots_wrap_uv
        # A first-ever install downloads uv *inside* the installer, i.e. after the
        # wrapper step found nothing to wrap, and pm then installs its own copy. One
        # retry wraps that uv and lets the same script finish; the clone, uv and
        # Python are already there, so it is the short path, not a second install.
        if ! pots_install_hermes; then
          echo "pots: hermes installer failed; wrapping uv again and retrying once"
          pots_wrap_uv
          pots_install_hermes
        fi
        # The installer publishes the command for login shells; link it into the
        # bin dir Pots Build's own PATH carries so probes find it by name.
        ln -sfn /root/.hermes/hermes-agent/.hermes/bin/hermes /root/.local/bin/hermes
        # Hermes' web panel ships with this app, built from one hermes-agent commit
        # (POTS_WEBAPP_PIN). The installer honours `--commit` only on a fresh clone —
        # on an existing checkout it fast-forwards to the branch's tip — so the pin
        # is applied here, after it, and the checkout is left detached at it. That is
        # the version the panel was built for: any other one and the panel refuses
        # to start, which is a sentence on the row instead of a blank page.
        if [ -n "${'$'}POTS_WEBAPP_PIN" ]; then
          if [ "${'$'}(git -C /root/.hermes/hermes-agent rev-parse HEAD)" != "${'$'}POTS_WEBAPP_PIN" ]; then
            echo "pots: pinning Hermes to ${'$'}POTS_WEBAPP_PIN, the commit this app's panel was built from"
            if git -C /root/.hermes/hermes-agent fetch --depth 1 origin "${'$'}POTS_WEBAPP_PIN" \
              && git -C /root/.hermes/hermes-agent checkout --force --detach "${'$'}POTS_WEBAPP_PIN"; then
              echo "pots: Hermes is at ${'$'}POTS_WEBAPP_PIN"
            else
              echo "pots: could not pin Hermes to ${'$'}POTS_WEBAPP_PIN; the panel will refuse until it is"
            fi
          fi
          # The renderer is not built here: `pnpm install` of apps/desktop cannot run
          # under proot's --link2symlink, which dpkg needs. The app unpacks the one
          # CI built on the panel's first start.
          echo "pots: the web panel renderer ships with the app"
        else
          # A developer build ships none, so the checkout's own bundle is the only
          # candidate. A failure here does not fail the agent — the panel then says
          # it is not built instead of showing an empty page, and `hermes webapp
          # --build-only` can be run again from a terminal.
          /root/.local/bin/hermes webapp --build-only || echo "pots: webapp bundle not built"
        fi
        # The wrapper has to leave uv and pm working, so prove both in this log. uv
        # is not on PATH (pm never exposes it), which is why the store path is the
        # thing to run, not `uv`.
        for f in "${'$'}POTS_UV_STORE"/uv-*/uv; do
          [ -x "${'$'}f" ] || continue
          echo "pots: ${'$'}f -> ${'$'}("${'$'}f" --version 2>&1)"
        done
        /root/.local/bin/hermes pm doctor || echo "pots: pm doctor reported a problem"
    """

    val all: ImmutableList<BuildAgent> = persistentListOf(
        BuildAgent(
            id = OPENCODE,
            title = "OpenCode",
            binary = "opencode",
            installCommand = OPENCODE_INSTALL.trimIndent(),
        ),
        BuildAgent(
            id = PI,
            title = "Pi",
            binary = "pi",
            installCommand = PI_INSTALL.trimIndent(),
            // Node, Pi itself and then pi-web from npm, which resolves and
            // unpacks a package tree of its own.
            installTimeoutSeconds = 1800,
        ),
        BuildAgent(
            id = HERMES,
            title = "Hermes",
            binary = "hermes",
            installCommand = HERMES_INSTALL.trimIndent(),
            // Its installer resolves uv and Python, installs the dependency tree,
            // builds the command, and then builds the webapp renderer — none of it
            // is a download. The budget covers a second attempt on the installer,
            // which is the short path: the clone, uv and Python are already there.
            installTimeoutSeconds = 5400,
        ),
    )

    fun get(id: String?): BuildAgent? = all.firstOrNull { it.id == id }
}
