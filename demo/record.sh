#!/usr/bin/env bash
# Records the demo <name> from demo/<name>/ into build/demo/<name>/ (spec section 13).
#
#   demo/record.sh <name>
#
# demo/<name>/web.mjs       Playwright script for the web view -> web.mp4, web.gif
# demo/<name>/terminal.tape VHS script for the terminal          -> terminal.mp4, terminal.gif
#
# Narrated lines (narrate() in web.mjs, `# say:` in a tape) become a voice-over in the .mp4
# and burned-in captions in both files (spec section 13.4).
set -euo pipefail

NAME="${1:?usage: demo/record.sh <name>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEMO="$ROOT/demo/$NAME"
OUT="$ROOT/build/demo/$NAME"
WEB_GIF_LIMIT=$((10 * 1024 * 1024))
TERMINAL_GIF_LIMIT=$((6 * 1024 * 1024))

fail() { echo "record: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || fail "$1 is missing; $2"; }

[ -d "$DEMO" ] || fail "no demo '$NAME' in demo/"
need ffmpeg "install it with: brew install ffmpeg"
need gh "install it with: brew install gh"
need node "install Node.js 18 or newer"
# The natural voice (Kokoro) is set up once; DEMO_TTS=say falls back to macOS say.
if [ "${DEMO_TTS:-kokoro}" = "kokoro" ]; then
  [ -x "$ROOT/demo/.venv/bin/python" ] && [ -s "$ROOT/demo/.kokoro/kokoro-v1.0.onnx" ] || "$ROOT/demo/setup-voice.sh"
else
  need say "DEMO_TTS=say needs macOS say"
fi

# Demos run against real GitHub repositories; the token avoids the anonymous rate limit.
export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
# Demo scans and stars go to their own scan log and stars file, not yours.
XDG_STATE_HOME="$(mktemp -d)"
XDG_DATA_HOME="$(mktemp -d)"
export XDG_STATE_HOME XDG_DATA_HOME

echo "record: building skill-atlas"
(cd "$ROOT" && ./gradlew -q installDist)
export PATH="$ROOT/build/install/skill-atlas/bin:$PATH"
rm -rf "$OUT"
mkdir -p "$OUT"

SERVER=""
cleanup() { if [ -n "$SERVER" ]; then kill "$SERVER" 2>/dev/null || true; fi; }
trap cleanup EXIT

# Playwright renders the caption bars for every demo, and records the web view.
(cd "$ROOT/demo" && { [ -d node_modules ] || npm install --silent --no-audit --no-fund; })
(cd "$ROOT/demo" && npx playwright install --only-shell chromium >/dev/null)
# GNU stat first: on Linux, `stat -f` means filesystem status and succeeds with the wrong output.
size() { stat -c %s "$1" 2>/dev/null || stat -f %z "$1"; }

if [ -f "$DEMO/web.mjs" ]; then

  PORT="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
  skill-atlas serve --port "$PORT" >"$OUT/serve.log" 2>&1 &
  SERVER=$!
  until curl -s -o /dev/null "http://127.0.0.1:$PORT/"; do
    kill -0 "$SERVER" 2>/dev/null || fail "skill-atlas serve exited; see $OUT/serve.log"
    sleep 0.2
  done

  echo "record: web view ($DEMO/web.mjs)"
  (cd "$ROOT/demo" && node "$DEMO/web.mjs" "http://127.0.0.1:$PORT/" "$OUT")
  cleanup
  SERVER=""

  (cd "$ROOT/demo" && node lib/finish.mjs "$OUT/web.webm" "$OUT/web.narration.json" "$OUT/web.mp4" "$OUT/web.gif" 960 8)
  [ "$(size "$OUT/web.gif")" -le "$WEB_GIF_LIMIT" ] || fail "web.gif is over 10 MB; shorten the script's steps"
fi

if [ -f "$DEMO/terminal.tape" ]; then
  need vhs "install it with: brew install vhs"
  echo "record: terminal ($DEMO/terminal.tape)"
  (cd "$ROOT/demo" && node lib/tape-narration.mjs "$DEMO/terminal.tape" "$OUT")
  (cd "$OUT" && vhs --output terminal.webm "$DEMO/terminal.tape")
  (cd "$ROOT/demo" && node lib/finish.mjs "$OUT/terminal.webm" "$OUT/terminal.narration.json" "$OUT/terminal.mp4" "$OUT/terminal.gif" 1000 10 "$(cat "$OUT/terminal.duration")")
  [ "$(size "$OUT/terminal.gif")" -le "$TERMINAL_GIF_LIMIT" ] || fail "terminal.gif is over 6 MB; shorten the tape's Sleep steps"
fi

echo "record: done"
ls -lh "$OUT" | awk 'NR > 1 { print "  " $5 "  " $9 }'
