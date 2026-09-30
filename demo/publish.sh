#!/usr/bin/env bash
# Publishes build/demo/<name>/ to the orphan `demos` branch as demos/pr-<number>/ and prints
# the Markdown to paste into the pull request (spec section 13.3).
#
#   demo/publish.sh <pr-number> <name>
set -euo pipefail

PR="${1:?usage: demo/publish.sh <pr-number> <name>}"
NAME="${2:?usage: demo/publish.sh <pr-number> <name>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/build/demo/$NAME"
[ -d "$SRC" ] || { echo "publish: no recording in $SRC; run demo/record.sh $NAME first" >&2; exit 1; }

REPO="$(cd "$ROOT" && gh repo view --json nameWithOwner --jq .nameWithOwner)"
WORK="$(mktemp -d)/demos"
cleanup() { git -C "$ROOT" worktree remove --force "$WORK" 2>/dev/null || true; }
trap cleanup EXIT

if git -C "$ROOT" ls-remote --exit-code --heads origin demos >/dev/null 2>&1; then
  git -C "$ROOT" fetch -q origin demos
  git -C "$ROOT" worktree add -q -B demos "$WORK" origin/demos
else
  # First demo ever: an orphan branch that shares no history with main.
  git -C "$ROOT" worktree add -q --orphan -b demos "$WORK"
  printf '# Demo recordings\n\nScripted demos for pull requests, one folder per PR (spec section 13).\nThis branch shares no history with `main`.\n' >"$WORK/README.md"
fi

TARGET="$WORK/demos/pr-$PR"
rm -rf "$TARGET"
mkdir -p "$TARGET"
cp "$SRC"/*.gif "$SRC"/*.mp4 "$TARGET"/ 2>/dev/null || true
[ -n "$(ls -A "$TARGET")" ] || { echo "publish: no .gif or .mp4 files in $SRC" >&2; exit 1; }

git -C "$WORK" add -A
git -C "$WORK" commit -q -m "Demo for PR #$PR ($NAME)"
git -C "$WORK" push -q origin demos

BASE="https://github.com/$REPO/blob/demos/demos/pr-$PR"
echo "publish: pushed demos/pr-$PR to the demos branch. Paste this into the PR:"
echo
for gif in "$TARGET"/*.gif; do
  file="$(basename "$gif")"
  stem="${file%.gif}"
  title="$(printf '%s' "$stem" | awk '{ print toupper(substr($0, 1, 1)) substr($0, 2) }')"
  echo "**$title demo**"
  echo
  echo "![${stem} demo]($BASE/$file?raw=true)"
  [ -f "$TARGET/$stem.mp4" ] && echo "[Full-quality video]($BASE/$stem.mp4)"
  echo
done
