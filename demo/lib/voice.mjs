// Renders narration lines (spec section 13.4). Original voices only, never an imitation.
//
// DEMO_TTS=kokoro (default): Kokoro, a local neural model, for a natural voice. Set it up once
//   with demo/setup-voice.sh. DEMO_VOICE picks the voice (default af_heart), DEMO_SPEED the pace.
// DEMO_TTS=say: macOS `say`, with DEMO_VOICE and DEMO_RATE; robotic, but needs nothing installed.
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const DEMO = join(dirname(fileURLToPath(import.meta.url)), "..");
export const ENGINE = process.env.DEMO_TTS || "kokoro";
const KOKORO = {
  python: join(DEMO, ".venv", "bin", "python"),
  model: join(DEMO, ".kokoro", "kokoro-v1.0.onnx"),
  voices: join(DEMO, ".kokoro", "voices-v1.0.bin"),
  voice: process.env.DEMO_VOICE || "af_heart",
  speed: process.env.DEMO_SPEED || "1.05",
};

/** Seconds of audio in a media file. */
export function durationOf(file) {
  const out = execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", file]);
  return parseFloat(out.toString());
}

/** Renders every line into voice/NN.wav under [outDir]; returns [{ file, duration }] in order. */
export function speakAll(outDir, texts) {
  const dir = join(outDir, "voice");
  mkdirSync(dir, { recursive: true });
  const lines = texts.map((text, i) => ({ text, file: join(dir, String(i + 1).padStart(2, "0") + ".wav") }));
  if (lines.length === 0) return [];

  if (ENGINE === "kokoro") {
    for (const path of [KOKORO.python, KOKORO.model, KOKORO.voices]) {
      if (!existsSync(path)) throw new Error(`voice: ${path} is missing; run demo/setup-voice.sh (or set DEMO_TTS=say)`);
    }
    const out = execFileSync(
      KOKORO.python,
      [join(DEMO, "lib", "kokoro_say.py"), KOKORO.model, KOKORO.voices, KOKORO.voice, KOKORO.speed],
      { input: JSON.stringify(lines), maxBuffer: 16 * 1024 * 1024 },
    );
    return JSON.parse(out.toString());
  }

  const voice = process.env.DEMO_VOICE || "Reed (English (US))";
  const rate = process.env.DEMO_RATE || "185";
  return lines.map(({ text, file }) => {
    execFileSync("say", ["-v", voice, "-r", rate, "--file-format=WAVE", "--data-format=LEI16@22050", "-o", file, text]);
    return { file, duration: durationOf(file) };
  });
}
