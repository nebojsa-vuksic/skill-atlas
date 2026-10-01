// Tour: starring skills in the web view (spec sections 5.11 and 13.5).
import { startWebDemo } from "../lib/web.mjs";

const [url, outDir] = process.argv.slice(2);
const LINES = {
  intro: "Some skills you reach for every day. Let's star them.",
  button: "Pick a skill and hit the star. It's saved right away.",
  key: "Or just press S. Starred skills get a gold star in the list.",
  only: "This star by the filter shows only your favourites.",
  words: "It's the is-starred filter, so it mixes with words. Two favourites mention Word.",
  reload: "Reload, and they're still starred. The terminal sees the very same stars.",
};
const demo = await startWebDemo(outDir, Object.values(LINES));
const { page } = demo;
// Keeps the whole split pane in view, above the caption bar.
const showList = () => page.locator("#skill-count").evaluate((el) => el.scrollIntoView({ block: "start" }));

await page.goto(url);
await demo.type("#url", "github.com/anthropics/skills", 45);
await page.click("#scan-button");
await demo.say(LINES.intro);
await demo.idle();
await showList();

await demo.skill("pdf").click();
await demo.pause(500);
await page.click("#star-button");
await page.waitForSelector("#star-button[aria-pressed=true]");
await demo.say(LINES.button);

await demo.skill("xlsx").click();
await demo.pause(400);
await page.keyboard.press("s");
await demo.skill("xlsx").locator(".star-icon").waitFor();
await demo.say(LINES.key);
await demo.skill("docx").click();
await demo.pause(400);
await page.keyboard.press("s");
await demo.skill("docx").locator(".star-icon").waitFor();
await demo.pause(800);

await page.click("#starred-only");
await demo.say(LINES.only);
await page.locator("#filter").press("End");
await demo.type("#filter", " word", 110);
await demo.say(LINES.words);

await page.locator("#filter").press("Escape");
await demo.pause(600);
await page.reload();
await demo.idle();
await showList();
await page.click("#starred-only");
await demo.say(LINES.reload);
await demo.finish();
