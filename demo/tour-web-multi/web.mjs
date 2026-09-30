// Tour 3 of 6: several repositories in the web view (spec sections 5.10 and 13.5).
import { startWebDemo } from "../lib/web.mjs";

const [url, outDir] = process.argv.slice(2);
const LINES = {
  intro: "Why stop at one repository? Paste three at once.",
  result: "Each one gets a chip, a row in the summary, and its own group in the list.",
  across: "Search runs across every one of them.",
  repo: "Want just one? Say repo, colon, and a name.",
  fixtures: "Koog's test fixtures aren't skills, so they're set aside.",
  similar: "Similar skills cross repository lines. This Koog skill points straight into the others.",
  jump: "One click, and you're in another repository.",
  remove: "Done with one? Hit the x. Gone.",
  outro: "Several repositories, one search. That's Skill Atlas on the web.",
};
const demo = await startWebDemo(outDir, Object.values(LINES));
const { page } = demo;

await page.goto(url);
await demo.say(LINES.intro);
await demo.type("#url", "github.com/JetBrains/MPS github.com/JetBrains/koog github.com/anthropics/skills", 30);
await page.click("#scan-button");
await demo.idle();
await demo.say(LINES.result);

await demo.type("#filter", "test", 110);
await demo.say(LINES.across);
await page.locator("#filter").fill("");
await demo.type("#filter", "repo:koog", 100);
await demo.say(LINES.repo);
await page.locator("#ignored").scrollIntoViewIfNeeded();
await demo.say(LINES.fixtures);

await demo.skill("split-jvm-nonjvm").click();
await page.locator("#similar").scrollIntoViewIfNeeded();
await demo.say(LINES.similar);
await page.locator(".similar-row", { has: page.locator(".similar-repo") }).first().click();
await demo.say(LINES.jump);

await page.evaluate(() => window.scrollTo(0, 0));
await page.locator(".chip", { hasText: "anthropics/skills" }).locator(".chip-remove").click();
await demo.idle();
await demo.say(LINES.remove);
await demo.say(LINES.outro);
await demo.finish();
