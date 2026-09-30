#!/usr/bin/env bash
# Sets up Kokoro, the local neural voice for demo narration (spec section 13.4). Run once.
# Everything stays inside demo/: the Python venv in demo/.venv, the model in demo/.kokoro/.
set -euo pipefail
DEMO="$(cd "$(dirname "$0")" && pwd)"
RELEASE="https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0"

[ -x "$DEMO/.venv/bin/python" ] || python3 -m venv "$DEMO/.venv"
"$DEMO/.venv/bin/pip" install -q --disable-pip-version-check kokoro-onnx soundfile
mkdir -p "$DEMO/.kokoro"
for file in kokoro-v1.0.onnx voices-v1.0.bin; do
  [ -s "$DEMO/.kokoro/$file" ] || curl -fsSL -o "$DEMO/.kokoro/$file" "$RELEASE/$file"
done
echo "setup-voice: Kokoro is ready ($(du -sh "$DEMO/.kokoro" | cut -f1) of model files)"
