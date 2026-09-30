// Narration for Playwright demo scripts (spec section 13.4).
//
//   const narrator = createNarrator(outDir, "web");
//   narrator.prepare([...all lines...]);   // render the voice up front, so recording has no dead air
//   narrator.start();                      // right after context.newPage(): the video starts here
//   await narrator.narrate(page, "Line");  // notes the time, then waits for the line to finish
//   narrator.save();                       // writes <prefix>.narration.json
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { speakAll } from "./voice.mjs";

export function createNarrator(outDir, prefix) {
  const rendered = new Map();
  const lines = [];
  let started = Date.now();

  const render = (text) => {
    if (!rendered.has(text)) throw new Error(`narrator: "${text}" wasn't passed to prepare()`);
    return rendered.get(text);
  };

  return {
    /** Renders every line in one go, before the recording starts, so it has no dead air. */
    prepare(texts) {
      const unique = [...new Set(texts)];
      speakAll(outDir, unique).forEach((result, i) => rendered.set(unique[i], result));
    },
    start() {
      started = Date.now();
    },
    async narrate(page, text, { gap = 400 } = {}) {
      const { file, duration } = render(text);
      lines.push({ at: (Date.now() - started) / 1000, duration, text, file });
      await page.waitForTimeout(duration * 1000 + gap);
    },
    save() {
      writeFileSync(join(outDir, prefix + ".narration.json"), JSON.stringify(lines, null, 2));
      return lines;
    },
  };
}
