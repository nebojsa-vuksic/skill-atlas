// Tour 1 of 6: the web view basics (spec sections 5.4, 5.6 and 13.5).
import { startWebDemo } from "../lib/web.mjs";

const [url, outDir] = process.argv.slice(2);
const LINES = {
  intro: "This is Skill Atlas. Hand it a GitHub repository, and it finds every agent skill inside.",
  scan: "One URL. One click. Let's go.",
  result: "Twenty skills, straight from the default branch, with the exact commit it read.",
  select: "Click any skill. You get the full description, where it lives, and the file itself, rendered.",
  raw: "Want the file exactly as written? Hit Raw.",
  divider: "Need more room? Drag the divider. It remembers.",
  similar: "Now the fun part. Similar skills. No AI, just honest math on the words.",
  jump: "Click one, and you're there. That's the basics.",
};
const demo = await startWebDemo(outDir, Object.values(LINES));
const { page } = demo;

await page.goto(url);
await demo.say(LINES.intro);
await demo.type("#url", "github.com/anthropics/skills", 45);
await demo.say(LINES.scan);
await page.click("#scan-button");
await demo.idle();
await demo.say(LINES.result);

await demo.skill("pdf").click();
await demo.say(LINES.select);
await page.click("#tab-raw");
await demo.say(LINES.raw);
await page.click("#tab-rendered");

const divider = await page.locator("#divider").boundingBox();
await page.mouse.move(divider.x + 4, divider.y + 200);
await page.mouse.down();
await page.mouse.move(divider.x + 160, divider.y + 200, { steps: 25 });
await page.mouse.up();
await demo.say(LINES.divider);

await page.locator("#similar").scrollIntoViewIfNeeded();
await demo.say(LINES.similar);
await page.locator(".similar-row").first().click();
await demo.say(LINES.jump);
await demo.finish();
