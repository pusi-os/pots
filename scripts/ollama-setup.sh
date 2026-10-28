#!/usr/bin/env bash
# Install (or detect) Ollama and start `ollama serve`.
# Pots talks to Ollama through its OpenAI-compatible endpoint
# http://localhost:11434/v1, so no in-app integration is needed.
set -euo pipefail

OLLAMA_VERSION="${OLLAMA_VERSION:-latest}"
INSTALL_DIR="${OLLAMA_INSTALL_DIR:-$HOME/.local}"
BIN_DIR="$INSTALL_DIR/bin"
OLLAMA_BIN="$BIN_DIR/ollama"

detect_os() {
    case "$(uname -s)" in
        Linux*)  echo linux ;;
        Darwin*) echo darwin ;;
        MINGW*|MSYS*|CYGWIN*) echo windows ;;
        *) echo "unknown" ;;
    esac
}

detect_arch() {
    case "$(uname -m)" in
        x86_64|amd64) echo amd64 ;;
        aarch64|arm64) echo arm64 ;;
        *) echo "unknown" ;;
    esac
}

OS="$(detect_os)"
ARCH="$(detect_arch)"

if command -v ollama >/dev/null 2>&1; then
    OLLAMA_BIN="$(command -v ollama)"
    echo "Ollama already installed: $OLLAMA_BIN"
elif [ -x "$OLLAMA_BIN" ]; then
    echo "Ollama already installed: $OLLAMA_BIN"
else
    echo "Ollama not found; installing..."
    mkdir -p "$BIN_DIR"
    if [ "$OS" = "linux" ]; then
        if [ "$ARCH" != "amd64" ] && [ "$ARCH" != "arm64" ]; then
            echo "Unsupported architecture: $ARCH" >&2; exit 1
        fi
        URL="https://ollama.com/download/ollama-linux-${ARCH}.tar.zst"
        TMP="$(mktemp)"
        echo "Downloading $URL"
        curl -fL --progress-bar -o "$TMP" "$URL"
        if command -v zstd >/dev/null 2>&1; then
            zstd -d -c "$TMP" | tar -xf - -C "$INSTALL_DIR"
        else
            echo "zstd not found; trying tar with zstd support" >&2
            tar --zstd -xf "$TMP" -C "$INSTALL_DIR"
        fi
        rm -f "$TMP"
    elif [ "$OS" = "darwin" ]; then
        URL="https://ollama.com/download/ollama-darwin.tgz"
        TMP="$(mktemp)"
        echo "Downloading $URL"
        curl -fL --progress-bar -o "$TMP" "$URL"
        tar -xzf "$TMP" -C "$INSTALL_DIR"
        rm -f "$TMP"
    else
        echo "Windows is not supported by this script." >&2
        echo "Download the installer from https://ollama.com/download and run it, then re-run this script." >&2
        exit 1
    fi
    echo "Installed to $OLLAMA_BIN"
fi

# Ensure on PATH for this session
export PATH="$BIN_DIR:$PATH"

# Start server if not running
if ! curl -sf http://127.0.0.1:11434/api/version >/dev/null 2>&1; then
    echo "Starting ollama serve..."
    nohup "$OLLAMA_BIN" serve >/tmp/ollama-serve.log 2>&1 &
    for i in $(seq 1 30); do
        if curl -sf http://127.0.0.1:11434/api/version >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done
fi

echo "Ollama version:"
curl -s http://127.0.0.1:11434/api/version
echo
echo
echo "Next steps:"
echo "  1. Pull a model:  scripts/ollama-models.sh pull qwen2.5:3b"
echo "  2. In Pots: Settings -> Services -> Add service -> OpenAI-compatible"
echo "     Base URL: http://localhost:11434/v1   (default)  Model: <name from 'ollama list'>"
