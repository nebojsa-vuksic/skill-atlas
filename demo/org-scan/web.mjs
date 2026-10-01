// Web view demo: every repository of an organization (spec sections 5.11 and 13).
import { startWebDemo } from "../lib/web.mjs";

const [url, outDir] = process.argv.slice(2);
const LINES = {
  intro: "Not just a repository. Paste a whole organization.",
  wait: "Skill Atlas lists its repositories, checks each one for skill files, and clones only those.",
  result: "One chip for the organization. Every repository with skills gets a row and a group.",
  searched: "And here's what was searched. Forks and archived repositories are skipped.",
  search: "Search runs across all of them at once.",
  repo: "Narrow it to one repository with repo, colon.",
  remove: "One click on the x, and the whole organization is gone.",
  outro: "A whole organization, one search. That's Skill Atlas.",
};
const demo = await startWebDemo(outDir, Object.values(LINES));
const { page } = demo;

await page.goto(url);
await demo.say(LINES.intro);
await demo.type("#url", "github.com/anthropics github.com/JetBrains/MPS", 30);
await page.click("#scan-button");
await demo.say(LINES.wait);
await demo.idle();
await demo.say(LINES.result);

await page.locator("#owner-lines").scrollIntoViewIfNeeded();
await demo.say(LINES.searched);

await demo.type("#filter", "pdf", 110);
await demo.say(LINES.search);
await page.locator("#filter").press("Home");
await demo.type("#filter", "repo:skills ", 100);
await demo.say(LINES.repo);

await page.evaluate(() => window.scrollTo(0, 0));
await page.locator(".chip.owner .chip-remove").click();
await demo.idle();
await demo.say(LINES.remove);
await demo.say(LINES.outro);
await demo.finish();
