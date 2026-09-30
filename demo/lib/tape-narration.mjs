// Places the `# say: …` lines of a VHS tape on its timeline and renders them (spec section 13.4).
//   node tape-narration.mjs <tape> <outDir>   ->  <outDir>/terminal.narration.json
// Fails when a line lasts longer than the time until the next line, or the end of the tape.
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { speakAll } from "./voice.mjs";

const [tape, outDir] = process.argv.slice(2);
const KEYS = new Set(["Enter", "Tab", "Backspace", "Delete", "Up", "Down", "Left", "Right", "Escape", "Space",
  "PageUp", "PageDown", "Home", "End", "Insert"]);

function seconds(value) {
  const match = /^([\d.]+)(ms|s|m)?$/.exec(value.trim());
  if (!match) throw new Error("tape-narration: can't read a duration: " + value);
  const n = parseFloat(match[1]);
  return match[2] === "ms" ? n / 1000 : match[2] === "m" ? n * 60 : n;
}

let typing = 0.05; // VHS's default TypingSpeed
let time = 0;
let recording = true;
const lines = [];
for (const raw of readFileSync(tape, "utf8").split("\n")) {
  const line = raw.trim();
  const say = /^#\s*say:\s*(.+)$/.exec(line);
  if (say) {
    lines.push({ at: time, text: say[1].trim() });
    continue;
  }
  if (line === "" || line.startsWith("#")) continue;
  const [command, ...rest] = line.split(/\s+/);
  const [name, speed] = command.split("@");
  const pace = speed ? seconds(speed) : typing;
  const add = (t) => { if (recording) time += t; };
  if (name === "Set" && rest[0] === "TypingSpeed") typing = seconds(rest[1]);
  else if (name === "Hide") recording = false;
  else if (name === "Show") recording = true;
  else if (name === "Sleep") add(seconds(rest[0]));
  else if (name === "Type") {
    const text = line.slice(line.indexOf('"') + 1, line.lastIndexOf('"'));
    add(text.length * pace);
  } else if (KEYS.has(name) || /^Ctrl\+/.test(name)) {
    const count = rest[0] && /^\d+$/.test(rest[0]) ? parseInt(rest[0], 10) : 1;
    add(count * pace);
  }
}

const end = time;
const rendered = speakAll(outDir, lines.map((line) => line.text));
lines.forEach((line, i) => {
  Object.assign(line, rendered[i]);
  const next = i + 1 < lines.length ? lines[i + 1].at : end;
  if (line.at + line.duration > next + 0.05) {
    console.error(`tape-narration: line ${i + 1} ("${line.text.slice(0, 40)}…") lasts ${line.duration.toFixed(1)} s ` +
      `but only ${(next - line.at).toFixed(1)} s pass before the next line; add a longer Sleep`);
    process.exit(1);
  }
});
writeFileSync(join(outDir, "terminal.narration.json"), JSON.stringify(lines, null, 2));
// The tape's true length, so finish.mjs can undo frames that VHS dropped (spec section 13.4).
writeFileSync(join(outDir, "terminal.duration"), end.toFixed(2));
console.log(`tape-narration: ${lines.length} lines over ${end.toFixed(1)} s`);
