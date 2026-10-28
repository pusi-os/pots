#!/usr/bin/env bash
# Helper for managing Ollama models used by Pots (OpenAI-compatible endpoint).
set -euo pipefail

OLLAMA_BIN="${OLLAMA_BIN:-$(command -v ollama 2>/dev/null || echo "$HOME/.local/bin/ollama")}"
BASE="http://127.0.0.1:11434"

cmd="${1:-list}"
shift || true

ensure_server() {
    if ! curl -sf "$BASE/api/version" >/dev/null 2>&1; then
        echo "Ollama server not running. Start it with: scripts/ollama-setup.sh" >&2
        exit 1
    fi
}

case "$cmd" in
    list)
        ensure_server
        echo "Installed models:"
        curl -s "$BASE/api/tags" | python3 -c '
import sys, json
d=json.load(sys.stdin)
for m in d.get("models", []):
    print(f"  {m[\"name\"]}  ({m.get(\"size\",0)//1024//1024} MB)")
'
        ;;
    pull)
        ensure_server
        model="${1:-}"
        if [ -z "$model" ]; then
            echo "Usage: $0 pull <model>" >&2; exit 1
        fi
        echo "Pulling $model ..."
        curl -sN -X POST "$BASE/api/pull" -d "{\"name\":\"$model\"}" | python3 -c '
import sys, json
for line in sys.stdin:
    line=line.strip()
    if not line: continue
    try:
        o=json.loads(line)
    except Exception:
        print(line); continue
    status=o.get("status","")
    total=o.get("total"); completed=o.get("completed")
    if total and completed:
        pct=completed*100//total
        print(f"\r{status}: {pct}%", end="", flush=True)
    else:
        print(f"\r{status}", end="", flush=True)
print()
'
        echo "Done. Use model id \"$model\" in Pots."
        ;;
    recommended)
        cat <<'EOF'
Small, capable models that run on a laptop CPU (pull with: scripts/ollama-models.sh pull <id>):

  qwen2.5:3b          - good general chat + tool use, ~2 GB
  qwen2.5:1.5b        - lighter, ~1 GB
  llama3.2:3b         - Meta, ~2 GB
  phi3.5:3.8b         - Microsoft, ~2.2 GB
  gemma2:2b           - Google, ~1.6 GB

After pulling, add in Pots: Settings -> Services -> Add service -> OpenAI-compatible
Base URL: http://localhost:11434/v1   Model: <id above>
EOF
        ;;
    rm|delete)
        ensure_server
        model="${1:-}"
        if [ -z "$model" ]; then echo "Usage: $0 rm <model>" >&2; exit 1; fi
        curl -s -X DELETE "$BASE/api/delete" -d "{\"name\":\"$model\"}" && echo "Deleted $model"
        ;;
    *)
        echo "Usage: $0 {list|pull <model>|recommended|rm <model>}" >&2
        exit 1
        ;;
esac
