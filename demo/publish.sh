#!/usr/bin/env bash
# Publishes recorded demos to the orphan `demos` branch and prints the Markdown for them
# (spec section 13.3).
#
#   demo/publish.sh <pr-number | label> <name>...
#
# A PR number publishes to demos/pr-<number>/, a label such as `tour` to demos/<label>/.
# One name puts its files straight into that folder; several get a subfolder each.
set -euo pipefail

TARGET="${1:?usage: demo/publish.sh <pr-number | label> <name>...}"
shift
[ "$#" -gt 0 ] || { echo "usage: demo/publish.sh <pr-number | label> <name>..." >&2; exit 2; }
case "$TARGET" in
  *[!0-9]*) FOLDER="$TARGET" ;;
  *) FOLDER="pr-$TARGET" ;;
esac
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
for NAME in "$@"; do
  [ -d "$ROOT/build/demo/$NAME" ] || { echo "publish: no recording in build/demo/$NAME; run demo/record.sh $NAME first" >&2; exit 1; }
done

REPO="$(cd "$ROOT" && gh repo view --json nameWithOwner --jq .nameWithOwner)"
WORK="$(mktemp -d)/demos"
cleanup() { git -C "$ROOT" worktree remove --force "$WORK" 2>/dev/null || true; }
trap cleanup EXIT

if [ "$FOLDER" = "latest" ]; then
  # The latest demos are the only published state (spec section 13.7): a fresh orphan commit
  # replaces the whole branch, so no older recording survives, not even in its history.
  git -C "$ROOT" branch -D demos-latest >/dev/null 2>&1 || true
  git -C "$ROOT" worktree add -q --orphan -b demos-latest "$WORK"
  printf '# Demo recordings\n\nThe latest demos of `main`, recorded by CI (spec section 13).\nThis branch has a single commit; each run replaces it.\n' >"$WORK/README.md"
elif git -C "$ROOT" ls-remote --exit-code --heads origin demos >/dev/null 2>&1; then
  git -C "$ROOT" fetch -q origin demos
  git -C "$ROOT" worktree add -q -B demos "$WORK" origin/demos
else
  # First demo ever: an orphan branch that shares no history with main.
  git -C "$ROOT" worktree add -q --orphan -b demos "$WORK"
  printf '# Demo recordings\n\nScripted demos for pull requests, one folder per PR (spec section 13).\nThis branch shares no history with `main`.\n' >"$WORK/README.md"
fi

# `latest` is the only published state (spec section 13.7): everything older goes.
if [ "$FOLDER" = "latest" ]; then rm -rf "$WORK/demos"; else rm -rf "$WORK/demos/$FOLDER"; fi
for NAME in "$@"; do
  if [ "$#" -eq 1 ]; then DEST="$WORK/demos/$FOLDER"; else DEST="$WORK/demos/$FOLDER/$NAME"; fi
  mkdir -p "$DEST"
  cp "$ROOT/build/demo/$NAME"/*.gif "$ROOT/build/demo/$NAME"/*.mp4 "$DEST"/ 2>/dev/null || true
  [ -n "$(ls -A "$DEST")" ] || { echo "publish: no .gif or .mp4 files in build/demo/$NAME" >&2; exit 1; }
done

git -C "$WORK" add -A
git -C "$WORK" commit -q -m "Demos in $FOLDER: $*${DEMO_SOURCE:+ (from $DEMO_SOURCE)}"
if [ "$FOLDER" = "latest" ]; then
  git -C "$WORK" push -q --force origin demos-latest:demos
else
  git -C "$WORK" push -q origin demos
fi

echo "publish: pushed demos/$FOLDER to the demos branch. Markdown:"
echo
for NAME in "$@"; do
  if [ "$#" -eq 1 ]; then SUB=""; else SUB="/$NAME"; fi
  BASE="https://github.com/$REPO/blob/demos/demos/$FOLDER$SUB"
  for gif in "$WORK/demos/$FOLDER$SUB"/*.gif; do
    file="$(basename "$gif")"
    stem="${file%.gif}"
    echo "**$NAME ($stem)**"
    echo
    echo "![$NAME $stem demo]($BASE/$file?raw=true)"
    [ -f "$WORK/demos/$FOLDER$SUB/$stem.mp4" ] && echo "[Video with voice-over]($BASE/$stem.mp4)"
    echo
  done
done
