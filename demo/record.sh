#!/usr/bin/env bash
# Records the demo <name> from demo/<name>/ into build/demo/<name>/ (spec section 13).
#
#   demo/record.sh <name>
#
# demo/<name>/web.mjs       Playwright script for the web view -> web.webm, web.mp4, web.gif
# demo/<name>/terminal.tape VHS script for the terminal          -> terminal.gif, terminal.mp4
set -euo pipefail

NAME="${1:?usage: demo/record.sh <name>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEMO="$ROOT/demo/$NAME"
OUT="$ROOT/build/demo/$NAME"
WEB_GIF_LIMIT=$((8 * 1024 * 1024))
TERMINAL_GIF_LIMIT=$((5 * 1024 * 1024))

fail() { echo "record: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || fail "$1 is missing; $2"; }

[ -d "$DEMO" ] || fail "no demo '$NAME' in demo/"
need ffmpeg "install it with: brew install ffmpeg"
need gh "install it with: brew install gh"

# Demos run against real GitHub repositories; the token avoids the anonymous rate limit.
export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
# Demo scans go to their own scan log, not yours.
XDG_STATE_HOME="$(mktemp -d)"
export XDG_STATE_HOME

echo "record: building skill-atlas"
(cd "$ROOT" && ./gradlew -q installDist)
export PATH="$ROOT/build/install/skill-atlas/bin:$PATH"
rm -rf "$OUT"
mkdir -p "$OUT"

SERVER=""
cleanup() { if [ -n "$SERVER" ]; then kill "$SERVER" 2>/dev/null || true; fi; }
trap cleanup EXIT

if [ -f "$DEMO/web.mjs" ]; then
  need node "install Node.js 18 or newer"
  (cd "$ROOT/demo" && { [ -d node_modules ] || npm install --silent --no-audit --no-fund; })
  (cd "$ROOT/demo" && npx playwright install --only-shell chromium >/dev/null)

  PORT="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
  skill-atlas serve --port "$PORT" >"$OUT/serve.log" 2>&1 &
  SERVER=$!
  until curl -s -o /dev/null "http://127.0.0.1:$PORT/"; do
    kill -0 "$SERVER" 2>/dev/null || fail "skill-atlas serve exited; see $OUT/serve.log"
    sleep 0.2
  done

  echo "record: web view ($DEMO/web.mjs)"
  (cd "$ROOT/demo" && node "$DEMO/web.mjs" "http://127.0.0.1:$PORT/" "$OUT/web.webm")
  cleanup
  SERVER=""

  ffmpeg -loglevel error -y -i "$OUT/web.webm" -c:v libx264 -pix_fmt yuv420p -movflags +faststart "$OUT/web.mp4"
  ffmpeg -loglevel error -y -i "$OUT/web.webm" \
    -vf "fps=10,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5" \
    "$OUT/web.gif"
  [ "$(stat -f %z "$OUT/web.gif" 2>/dev/null || stat -c %s "$OUT/web.gif")" -le "$WEB_GIF_LIMIT" ] ||
    fail "web.gif is over 8 MB; shorten the script's pauses or steps"
fi

if [ -f "$DEMO/terminal.tape" ]; then
  need vhs "install it with: brew install vhs"
  echo "record: terminal ($DEMO/terminal.tape)"
  (cd "$OUT" && vhs "$DEMO/terminal.tape")
  [ "$(stat -f %z "$OUT/terminal.gif" 2>/dev/null || stat -c %s "$OUT/terminal.gif")" -le "$TERMINAL_GIF_LIMIT" ] ||
    fail "terminal.gif is over 5 MB; shorten the tape's Sleep steps"
fi

echo "record: done"
ls -lh "$OUT" | awk 'NR > 1 { print "  " $5 "  " $9 }'
