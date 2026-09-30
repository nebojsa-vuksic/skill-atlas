// Shared setup for narrated web view demos (spec sections 13.2 and 13.4).
//
//   const demo = await startWebDemo(outDir, LINES);   // LINES: every narrated line, rendered up front
//   await demo.page.goto(url);
//   await demo.say("…");                              // narrate, and wait until the line is done
//   await demo.finish();                              // writes web.webm and web.narration.json
import { rename } from "node:fs/promises";
import { join } from "node:path";
import { chromium } from "playwright";
import { createNarrator } from "./narrator.mjs";

export async function startWebDemo(outDir, lines, { width = 1280, height = 800 } = {}) {
  const narrator = createNarrator(outDir, "web");
  narrator.prepare(lines);
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width, height },
    recordVideo: { dir: outDir, size: { width, height } },
  });
  const page = await context.newPage();
  narrator.start();
  return {
    page,
    say: (text) => narrator.narrate(page, text),
    pause: (ms) => page.waitForTimeout(ms),
    /** Types like a person, so viewers can follow. */
    type: (selector, text, delay = 55) => page.locator(selector).pressSequentially(text, { delay }),
    idle: () => page.waitForSelector("body[data-state=idle]", { timeout: 180_000 }),
    /** The list item of the skill named exactly [name]. */
    skill: (name) => page.locator("[role=option]").filter({ has: page.locator(".skill-name", { hasText: new RegExp("^" + name + "$") }) }),
    async finish() {
      narrator.save();
      const video = page.video();
      await context.close();
      await browser.close();
      await rename(await video.path(), join(outDir, "web.webm"));
    },
  };
}
