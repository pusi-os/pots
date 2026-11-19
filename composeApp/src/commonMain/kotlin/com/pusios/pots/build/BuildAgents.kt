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
     * OpenCode's own installer, fetched from its repository rather than from
     * `https://opencode.ai/install`: that URL answers 307 through Cloudflare from
     * a sandbox, while the script behind it is a plain file on GitHub. The script
     * itself downloads the static release binary from GitHub Releases into
     * `$HOME/.opencode/bin` and resolves the newest version on its own, so nothing
     * here has to pin one.
     */
    private const val OPENCODE_INSTALL = """
        set -e
        mkdir -p /root/.opencode/bin
        curl -fsSL https://raw.githubusercontent.com/anomalyco/opencode/refs/heads/v1/install -o /tmp/pots-opencode-install.sh
        # stdin closed: no answer is coming from a sandbox if the installer asks.
        bash /tmp/pots-opencode-install.sh < /dev/null
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
     * Two things about this command are not obvious and are both needed on a
     * phone inside proot:
     *
     * - **uv must copy, not hardlink.** `hermes pm` installs the Python tree
     *   with uv, which normally hardlinks its cache into the venv; the kernel
     *   answers `EPERM` inside an Android app's data dir. proot's
     *   `--link2symlink` covers `link()` *and* `linkat()`, so the refusal is not
     *   a missing proot flag — uv has to be told to copy. It cannot be told
     *   through the environment: `pm` strips every `UV_*` variable from the
     *   environment it hands uv (`pm/environment.py`, `_base_environment` keeps
     *   index settings only), which is why upstream's `UV_LINK_MODE=copy` report
     *   saw no effect. A project-level `uv.toml` is read by uv itself, so that
     *   is where the setting lives; it is git-ignored through
     *   `.git/info/exclude` because the installer stashes and resets a dirty
     *   checkout on every rerun and would otherwise take the file away.
     *
     *   That file reaches only the stages uv runs in the checkout. The stage the
     *   device failed on is `pm`'s own runtime (`installs/<id>/pm-runtime`),
     *   which `pm` builds with `no_config=True` and therefore with
     *   `uv --no-config` — no config file is read there, and the same
     *   `_base_environment` strips the variables. The lever for that stage is
     *   the clean slate below: the failed attempt's `pm-runtime` and uv cache are
     *   deleted, because uv seeds a cache by hardlinking out of an existing
     *   environment, which is the direction the device log shows. The link probe
     *   below then says, on the next attempt, whether copying was even the right
     *   remedy.
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
        if [ ! -d /root/.hermes/hermes-agent/.git ]; then
          git clone --depth 1 https://github.com/NousResearch/hermes-agent.git /root/.hermes/hermes-agent
        fi
        printf 'link-mode = "copy"\nno-cache = true\n' > /root/.hermes/hermes-agent/uv.toml
        grep -qxF uv.toml /root/.hermes/hermes-agent/.git/info/exclude 2>/dev/null \
          || echo uv.toml >> /root/.hermes/hermes-agent/.git/info/exclude
        # A failed attempt leaves a half-built PM runtime and a uv cache seeded
        # from it; the retry then hardlinks that environment back into the cache
        # and gets EPERM again. Nothing is installed yet, so nothing is lost by
        # starting the rebuild clean.
        if [ ! -x /root/.hermes/hermes-agent/.hermes/bin/hermes ]; then
          rm -rf /root/.hermes/installs/*/pm-runtime /root/.hermes/cache/uv
        fi
        # Whether a hardlink works at all in this guest decides whether the copy
        # mode above is even the right remedy, so the log has to carry the answer.
        echo "pots: link probe"
        ( cd /root || exit 1
          echo a > pots-link-probe
          if ln -f pots-link-probe pots-link-probe-2 2>&1; then echo "  ln: ok"; else echo "  ln: FAILED (exit ${'$'}?)"; fi
          ls -li pots-link-probe pots-link-probe-2 2>&1
          if python3 -c "import os; os.link('pots-link-probe','pots-link-probe-3')" 2>&1; then echo "  os.link: ok"; else echo "  os.link: FAILED (exit ${'$'}?)"; fi
          rm -f pots-link-probe pots-link-probe-2 pots-link-probe-3 )
        # stdin closed: no answer is coming from a sandbox if the installer asks.
        bash /root/.hermes/hermes-agent/scripts/install.sh --non-interactive --skip-browser --skip-computer-use --channel main < /dev/null
        # The installer publishes the command for login shells; link it into the
        # bin dir Pots Build's own PATH carries so probes find it by name.
        ln -sfn /root/.hermes/hermes-agent/.hermes/bin/hermes /root/.local/bin/hermes
        # Hermes' web panel: the renderer bundle `hermes webapp` serves. A failure
        # here does not fail the agent — the panel then says it is not built
        # instead of showing an empty page, and `hermes webapp --build-only` can be
        # run again from a terminal.
        /root/.local/bin/hermes webapp --build-only || echo "pots: webapp bundle not built"
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
            // is a download.
            installTimeoutSeconds = 3600,
        ),
    )

    fun get(id: String?): BuildAgent? = all.firstOrNull { it.id == id }
}
