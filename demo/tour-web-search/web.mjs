// Tour 2 of 6: big repositories and the filter in the web view (spec sections 4.4, 5.5 and 13.5).
import { startWebDemo } from "../lib/web.mjs";

const [url, outDir] = process.argv.slice(2);
const LINES = {
  intro: "Now something bigger. JetBrains MPS keeps a hundred and fourteen skill files.",
  merged: "Skill Atlas finds forty one real skills. Copies are merged, and product skills are flagged.",
  detail: "Here's one. Three identical copies, one entry, and a diamond because it ships inside the product.",
  filter: "Type to filter. Every word has to match the name or the description.",
  snippet: "When the match hides deep in a description, you get a snippet around it, highlighted.",
  none: "No match? It tells you straight.",
  clear: "Escape clears it, and your selection comes right back.",
  reload: "The filter lives in the address. Reload, and you're right where you were.",
};
const demo = await startWebDemo(outDir, Object.values(LINES));
const { page } = demo;

await page.goto(url);
await demo.type("#url", "github.com/JetBrains/MPS", 45);
await page.click("#scan-button");
await demo.say(LINES.intro);
await demo.idle();
await demo.say(LINES.merged);

await demo.skill("mps-tests").click();
await demo.say(LINES.detail);

await demo.type("#filter", "test", 110);
await demo.say(LINES.filter);
await demo.skill("mps-aspect-typesystem").scrollIntoViewIfNeeded();
await demo.say(LINES.snippet);

await page.locator("#filter").fill("");
await demo.type("#filter", "zzz", 120);
await demo.say(LINES.none);
await page.locator("#filter").press("Escape");
await demo.say(LINES.clear);

await demo.type("#filter", "generator", 90);
await demo.pause(600);
await page.reload();
await demo.idle();
await demo.say(LINES.reload);
await demo.finish();
