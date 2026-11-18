package com.pusios.pots.build

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The coding agents Pots Build can install on a fresh Debian. Each one ships a
 * self-contained installer, so the base system plus curl is all they need.
 *
 * Install locations (guest paths under `/root`):
 * - Pi → `~/.local/bin/pi`, on a Node.js runtime unpacked to `~/.local/share/pi-node`
 * - Hermes → `~/.hermes/venv/bin/hermes`, linked into `~/.local/bin/hermes`
 *
 * Proot injects both bin dirs into PATH for probes/installers. Login shells
 * rebuild PATH from profile files, so Pots Build also writes a profile.d snippet
 * and launches agents by absolute path.
 *
 * Both agents are pointed at the provider Pots itself is using, by
 * [SandboxAgentConfig] — nobody should have to log each CLI in separately.
 */
@Immutable
data class BuildAgent(
    val id: String,
    /** Product name — shown as-is, not translated. */
    val title: String,
    val binary: String,
    val installCommand: String,
)

object BuildAgents {

    const val PI = "pi"
    const val HERMES = "hermes"

    /**
     * Node.js is unpacked into the home rather than installed from apt, because
     * Pi needs 22.19+ and Debian bookworm's nodejs is 18. The version is resolved
     * from nodejs.org's own index for the running architecture, so this works on
     * every phone Debian runs on (arm64, armhf, x86).
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
        export PATH="/root/.local/share/pi-node/bin:${'$'}PATH"
        npm install -g --ignore-scripts --no-fund --no-audit --prefix /root/.local \
          @earendil-works/pi-coding-agent
    """

    /**
     * Hermes comes from PyPI into its own venv: the wheel declares Python
     * 3.11–3.13, which is what bookworm ships, and a venv keeps its dependency
     * tree (it pins `openai`, `httpx` and friends) away from the system one.
     */
    private const val HERMES_INSTALL = """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq || true
        apt-get install -y --no-install-recommends python3-venv python3-pip
        python3 -m venv /root/.hermes/venv
        /root/.hermes/venv/bin/pip install --no-cache-dir --upgrade pip
        /root/.hermes/venv/bin/pip install --no-cache-dir hermes-agent
        mkdir -p /root/.local/bin
        ln -sfn /root/.hermes/venv/bin/hermes /root/.local/bin/hermes
    """

    val all: ImmutableList<BuildAgent> = persistentListOf(
        BuildAgent(
            id = PI,
            title = "Pi",
            binary = "pi",
            installCommand = PI_INSTALL.trimIndent(),
        ),
        BuildAgent(
            id = HERMES,
            title = "Hermes",
            binary = "hermes",
            installCommand = HERMES_INSTALL.trimIndent(),
        ),
    )

    fun get(id: String?): BuildAgent? = all.firstOrNull { it.id == id }
}
