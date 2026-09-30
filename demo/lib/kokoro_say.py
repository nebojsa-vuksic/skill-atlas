"""Renders narration lines with Kokoro, a local neural text-to-speech model (spec section 13.4).

    kokoro_say.py <model.onnx> <voices.bin> <voice> <speed>  < lines.json  > results.json

lines.json is a list of {"text": ..., "file": ...}; each line is written as a WAV file and the
result lists each file's duration in seconds. The model loads once for all lines.
"""
import json
import sys
import warnings

warnings.filterwarnings("ignore")

import soundfile
from kokoro_onnx import Kokoro


def main():
    model, voices, voice, speed = sys.argv[1:5]
    kokoro = Kokoro(model, voices)
    results = []
    for line in json.load(sys.stdin):
        samples, rate = kokoro.create(line["text"], voice=voice, speed=float(speed), lang="en-us")
        soundfile.write(line["file"], samples, rate)
        results.append({"file": line["file"], "duration": len(samples) / rate})
    json.dump(results, sys.stdout)


if __name__ == "__main__":
    main()
