#!/usr/bin/env bash
# Turns every raw demo recording in build/demo/ into a narrated .mp4 and a captioned .gif
# (spec sections 13.2 and 13.6). CI runs it after `./gradlew demoTest`.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/demo"
[ -d node_modules ] || npm ci --silent --no-audit --no-fund
npx playwright install --only-shell chromium >/dev/null

for dir in "$ROOT"/build/demo/*/; do
  name="$(basename "$dir")"
  if [ -f "$dir/web.webm" ]; then
    node lib/finish.mjs "$dir/web.webm" "$dir/web.narration.json" "$dir/web.mp4" "$dir/web.gif" 960 8
  elif [ -f "$dir/terminal.webm" ]; then
    node lib/tape-narration.mjs "$dir/terminal.tape" "$dir"
    node lib/finish.mjs "$dir/terminal.webm" "$dir/terminal.narration.json" "$dir/terminal.mp4" "$dir/terminal.gif" 1000 10 "$(cat "$dir/terminal.duration")"
  else
    echo "finish-all: $name has no recording, skipping" >&2
  fi
done
