// Turns a raw recording into the published files (spec section 13.4): burns in the caption
// bars, mixes in the voice-over, and writes the .mp4 and the .gif.
//   node finish.mjs <raw video> <narration.json | -> <out.mp4> <out.gif> <gif width> <gif fps> [true length in s]
// With a true length (terminal tapes), a video that came out more than 3 % short, because VHS
// dropped frames, is stretched back to it, so the voice and captions line up with the screen.
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { chromium } from "playwright";

const [raw, narrationFile, mp4, gif, gifWidth, fps, trueLength] = process.argv.slice(2);
const lines = narrationFile !== "-" && existsSync(narrationFile) ? JSON.parse(readFileSync(narrationFile, "utf8")) : [];

function ffmpeg(args) {
  execFileSync("ffmpeg", ["-loglevel", "error", "-y", ...args], { stdio: "inherit" });
}

const [width] = execFileSync("ffprobe", ["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height",
  "-of", "csv=p=0", raw]).toString().trim().split(",").map(Number);

// Caption bars, rendered as transparent PNGs by headless Chromium.
const captions = [];
if (lines.length > 0) {
  const dir = join(dirname(mp4), "captions");
  mkdirSync(dir, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width, height: 400 } });
  await page.setContent(`<body style="margin:0;background:transparent;display:flex;justify-content:center;align-items:flex-start">
    <div id="c" style="max-width:${Math.round(width * 0.82)}px;padding:12px 22px;border-radius:12px;
      background:rgba(17,20,24,0.85);color:#fff;font:600 ${Math.round(width / 50)}px/1.35 -apple-system,'Segoe UI',sans-serif;
      text-align:center;box-shadow:0 4px 18px rgba(0,0,0,.35)"></div></body>`);
  for (const [i, line] of lines.entries()) {
    await page.locator("#c").evaluate((node, text) => { node.textContent = text; }, line.text);
    const file = join(dir, String(i + 1).padStart(2, "0") + ".png");
    await page.locator("#c").screenshot({ path: file, omitBackground: true });
    captions.push(file);
  }
  await browser.close();
}

const inputs = ["-i", raw];
const filters = [];
let video = "0:v";
const recorded = parseFloat(execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", raw]).toString());
if (trueLength && recorded < parseFloat(trueLength) * 0.97) {
  const factor = (parseFloat(trueLength) / recorded).toFixed(4);
  console.log(`finish: the recording is ${recorded.toFixed(1)} s but the tape runs ${trueLength} s; stretching by ${factor}`);
  filters.push(`[0:v]setpts=PTS*${factor}[stretched]`);
  video = "stretched";
}
lines.forEach((line, i) => inputs.push("-i", line.file));
captions.forEach((file) => inputs.push("-i", file));
captions.forEach((_, i) => {
  const from = lines[i].at.toFixed(2);
  const to = (lines[i].at + lines[i].duration + 0.3).toFixed(2);
  const out = `v${i + 1}`;
  filters.push(`[${video}][${1 + lines.length + i}:v]overlay=x=(W-w)/2:y=H-h-${Math.round(width / 40)}:enable='between(t,${from},${to})'[${out}]`);
  video = out;
});
if (lines.length > 0) {
  lines.forEach((line, i) => {
    const ms = Math.round(line.at * 1000);
    filters.push(`[${i + 1}:a]adelay=${ms}:all=1[a${i + 1}]`);
  });
  filters.push(lines.map((_, i) => `[a${i + 1}]`).join("") + `amix=inputs=${lines.length}:normalize=0:dropout_transition=0,apad[audio]`);
}

const map = lines.length > 0 ? ["-map", `[${video}]`, "-map", "[audio]", "-shortest", "-c:a", "aac", "-b:a", "128k"] : ["-map", "0:v"];
ffmpeg([...inputs, ...(filters.length ? ["-filter_complex", filters.join(";")] : []), ...map,
  "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", mp4]);
ffmpeg(["-i", mp4, "-vf",
  `fps=${fps},scale=${gifWidth}:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5`,
  gif]);
console.log(`finish: ${mp4} and ${gif}` + (lines.length ? ` with ${lines.length} narrated lines` : ""));
