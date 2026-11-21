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
    /**
     * A step that has to run before [installCommand] and cannot share its proot
     * call: a package manager needs proot's hardlink emulation, and the rest of
     * the install must not have it. Null for an agent whose install is one call.
     */
    val prereqCommand: String? = null,
    /** How long [prereqCommand] may run. */
    val prereqTimeoutSeconds: Long = 300,
    /**
     * What an earlier install left behind that this one must not inherit. The
     * paths are not trusted as they stand: [AgentVersions.debrisPlan] filters
     * them through [AgentVersions.mayDelete] first.
     */
    val debrisPaths: List<String> = emptyList(),
)

/** One proot call of an install, and whether it may carry the hardlink emulation. */
data class AgentInstallCall(
    val command: String,
    val timeoutSeconds: Long,
    val hardlinkEmulation: Boolean,
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
     * **The latest Hermes, and nothing pinned.** The checkout is one commit deep
     * on `main` and the installer is handed `--channel main` and no `--commit`: a
     * pinned install makes the installer walk the history to prove the commit is
     * an ancestor of the branch — history a depth-1 clone does not carry — and it
     * leaves the sandbox a release behind the renderer it could build for itself.
     * The commit `hermes-webapp.pin` names is the *fallback's* address, not the
     * install's: when the device cannot build a renderer, Pots Build moves the
     * checkout to that commit and unpacks the bundle CI built from it
     * ([HermesWebapp.pinCheckout]). One thing is still fetched: the release tags,
     * because `hermes --version` names the newest tag reachable from HEAD and a
     * depth-1 clone carries none — without that fetch the agent row reads
     * `git.<sha>` where the release number belongs.
     *
     * **No hardlink emulation, and no uv wrapper.** Inside proot on a phone, uv
     * hardlinks its cache into the venv and the guest refuses (`failed to hardlink
     * file from …: Operation not permitted`). The artifacts it hardlinks carry a
     * `.l2s.` prefix — proot's `--link2symlink` emulation (`l2s` = link-to-symlink)
     * had already turned an earlier hardlink into a symlink, and hardlinking *that*
     * on is what answers `EPERM`. `--link-mode` cannot help by construction: uv
     * 0.13.5 hands the mode to the wheel linker only, while filling the cache
     * (`persist_archive_file`) calls `fs_err::hard_link` with no mode at all. Nor
     * can configuration: `hermes pm` strips every `UV_*` variable and builds its
     * runtime with `--no-config`, so neither the environment nor a `uv.toml`
     * reaches that call. Wrapping the store binary and rewriting pm's digest of it
     * was the previous answer — a race against pm's own `ensure(verify=True)`, and
     * it is gone.
     *
     * What fixes it is not giving uv the emulation at all. dpkg is the only thing
     * that needs it, and dpkg runs exactly once in this install: `xz-utils`, which
     * is [prereqCommand] and gets a proot call of its own. This script is the
     * second call, without the emulation. Every leftover of an earlier install that
     * could still carry `.l2s.` artifacts — the checkout, the pm runtime, uv's
     * cache and its store — is removed before either call starts
     * ([BuildAgent.debrisPaths], filtered through [AgentVersions.mayDelete]).
     *
     * **`--channel main`.** The default stable channel makes the installer fetch a
     * release tag's entire history into the shallow clone — 643k objects, over 45
     * minutes on a phone — and then keep the existing checkout anyway. `main`
     * follows the branch and skips that fetch.
     */
    private const val HERMES_INSTALL = """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        mkdir -p /root/.hermes /root/.local/bin
        # Set when the repair ladder gave up, so a repeated call does not try to
        # download the 5 GiB checkout again to fail the same way. The install calls
        # the ladder once; the second call is the repair test's.
        POTS_CHECKOUT_DEAD=''
        # The clone rule, in one place: the repair ladder below re-clones with the
        # same flags. One commit deep and on `main` — nothing in this install walks
        # the history, so nothing needs it, and the release tags are fetched
        # separately below for the version.
        # The URL is a variable only so a unit test can point the ladder at a local
        # repository; on a device nothing sets it.
        pots_clone_hermes() {
          POTS_REPO=${'$'}{POTS_HERMES_REPO:-https://github.com/NousResearch/hermes-agent.git}
          git clone --depth 1 --branch main "${'$'}POTS_REPO" "${'$'}1"
        }
        if [ ! -d /root/.hermes/hermes-agent/.git ]; then
          pots_clone_hermes /root/.hermes/hermes-agent
        fi
        # A checkout is usable only if HEAD resolves and the working tree carries the
        # installer. `git clone` creates `.git` first and the files last, so a run
        # killed in between — proot gone, network gone, disk full — leaves `.git`
        # without a tree. Nothing after this point works on it: `install.sh` is not
        # there.
        pots_checkout_usable() {
          git -C "${'$'}1" rev-parse HEAD >/dev/null 2>&1 && test -f "${'$'}1/scripts/install.sh"
        }
        # The ladder, cheapest step first: 5 GiB and tens of minutes of download sit at
        # the end of it. Every step is judged by [pots_checkout_usable], never by an exit
        # code — `git reset --hard` answers 0 on a HEAD that does not resolve, and a
        # step whose output is piped to `head` answers with head's status.
        pots_ensure_hermes_checkout() {
          if [ -n "${'$'}POTS_CHECKOUT_DEAD" ]; then
            echo "pots: hermes checkout: broken (${'$'}POTS_CHECKOUT_DEAD)"
            return 1
          fi
          POTS_BROKEN=''
          if [ ! -d "${'$'}1/.git" ]; then
            POTS_BROKEN='no .git'
          elif ! git -C "${'$'}1" rev-parse HEAD >/dev/null 2>&1; then
            POTS_BROKEN='HEAD does not resolve'
          elif [ ! -f "${'$'}1/scripts/install.sh" ]; then
            POTS_BROKEN='scripts/install.sh is missing'
          fi
          if [ -z "${'$'}POTS_BROKEN" ]; then
            return 0
          fi
          echo "pots: hermes checkout: broken (${'$'}POTS_BROKEN)"
          # For whoever reads this log next: free space and what git thinks is dirty
          # are the two answers that decide the case, and guessing them already cost a
          # round trip on the device.
          df -h /root 2>/dev/null | tail -1
          git -C "${'$'}1" status --short 2>/dev/null | head -3
          # Step 1: put back whatever HEAD has and the tree does not. This is the whole
          # repair for the state the device was in.
          git -C "${'$'}1" checkout --force --detach HEAD >/dev/null 2>&1 || true
          if pots_checkout_usable "${'$'}1"; then
            echo "pots: hermes checkout: repaired at HEAD"
            return 0
          fi
          # Step 2: HEAD has nothing to restore from — a clone that never finished, or a
          # repository with no commits at all. `reset --hard` answers 0 and changes
          # nothing there, so only a fresh clone is left, and it goes last.
          rm -rf "${'$'}1"
          if pots_clone_hermes "${'$'}1" && pots_checkout_usable "${'$'}1"; then
            echo "pots: hermes checkout: re-cloned"
            return 0
          fi
          POTS_CHECKOUT_DEAD='the repair failed'
          # The owner reads this on a phone: name the cause, not "install failed".
          POTS_FREE_KB=${'$'}(df -Pk /root 2>/dev/null | tail -1 | awk '{print ${'$'}4}')
          case "${'$'}POTS_FREE_KB" in
            ''|*[!0-9]*) POTS_FREE_KB='' ;;
          esac
          if [ -n "${'$'}POTS_FREE_KB" ] && [ "${'$'}POTS_FREE_KB" -lt 1048576 ]; then
            echo "pots: hermes checkout: not enough free space on /root (${'$'}{POTS_FREE_KB} KiB); free space and retry"
          else
            echo "pots: hermes checkout: the re-clone did not finish (network?); retry when the device is online"
          fi
          return 1
        }
        # The installer cannot run on a checkout that is not there: stop here, with the
        # reason as the last line of the log, instead of dying inside install.sh.
        pots_ensure_hermes_checkout /root/.hermes/hermes-agent || exit 1
        # The installer's own channel, always `main`, and never a pinned commit: the
        # owner installs the latest Hermes and the renderer is built from whatever
        # that checkout is. A pinned install would walk the history to prove the
        # commit is an ancestor of the branch — history a depth-1 clone does not
        # carry — and it would leave the sandbox a release behind. The pin is only
        # the fallback's address, and that fallback moves the checkout itself.
        # stdin closed: no answer is coming from a sandbox if the installer asks.
        bash /root/.hermes/hermes-agent/scripts/install.sh --non-interactive --skip-browser --skip-computer-use --channel main < /dev/null
        # The installer publishes the command for login shells; link it into the
        # bin dir Pots Build's own PATH carries so probes find it by name.
        ln -sfn /root/.hermes/hermes-agent/.hermes/bin/hermes /root/.local/bin/hermes
        # Hermes derives its version from the newest release tag reachable from HEAD,
        # and the `--depth 1` clone above carries no tags at all: without this fetch
        # `hermes --version` — the version the agent row shows — answers `git.<sha>`
        # for ever. Ref-only, no file contents.
        ${HermesWebapp.TAGS_FETCH} \
          || echo "pots: could not fetch the Hermes release tags; its version stays undetermined"
        # The renderer is not built here: its build is a pnpm install of
        # apps/desktop, and pnpm 12's staged renames answer ENOENT under proot's
        # emulation. Pots Build builds it afterwards in a proot call of its own
        # without the emulation, stamps the commit it was built from, and falls
        # back to the bundle this app ships (see HermesWebapp) when every attempt
        # failed.
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
            // Its installer resolves uv and Python, installs the dependency tree and
            // builds the command — none of it is a download.
            installTimeoutSeconds = 5400,
            // The only package-manager step in the whole chain: `scripts/install.sh`
            // calls apt/dpkg never, and `tar -xJ` needs xz, which a Debian base does
            // not carry. dpkg is also the only reason proot's hardlink emulation is
            // needed at all, so this step — and nothing else — gets it.
            prereqCommand = """
                apt-get install -y --no-install-recommends xz-utils >/dev/null 2>&1 \
                  || echo "pots: xz-utils is missing; the panel cannot unpack its renderer"
            """.trimIndent(),
            // Everything an earlier install built, and nothing the owner owns: uv's
            // cache and pm's runtime may be full of proot's `.l2s.` artifacts, and
            // hardlinking one of those is what answers EPERM. `keepPaths` covers the
            // credentials, state and board, and [AgentVersions.mayDelete] is what
            // decides — a path it refuses is logged, not taken.
            debrisPaths = listOf(
                "/root/.hermes/hermes-agent",
                "/root/.hermes/installs",
                "/root/.hermes/cache/uv",
                "/root/.hermes/tools",
                "/root/.hermes/.pots-uv-copy-mode",
            ),
        ),
    )

    fun get(id: String?): BuildAgent? = all.firstOrNull { it.id == id }

    /**
     * The proot calls an install makes, in order, each with whether it may run with
     * proot's hardlink emulation.
     *
     * dpkg needs `--link2symlink` and uv must not have it (see [HERMES_INSTALL]),
     * and one proot call cannot be both. So an agent whose install needs a package
     * manager declares it as [BuildAgent.prereqCommand] and gets two calls: the
     * prerequisite with the emulation, the install without. An agent that declares
     * none keeps the single call it has always had, emulation and all.
     */
    fun installCalls(agent: BuildAgent): List<AgentInstallCall> {
        val prereq = agent.prereqCommand
        return buildList {
            if (prereq != null) {
                add(AgentInstallCall(prereq, agent.prereqTimeoutSeconds, hardlinkEmulation = true))
            }
            add(
                AgentInstallCall(
                    command = agent.installCommand,
                    timeoutSeconds = agent.installTimeoutSeconds,
                    hardlinkEmulation = prereq == null,
                ),
            )
        }
    }
}
