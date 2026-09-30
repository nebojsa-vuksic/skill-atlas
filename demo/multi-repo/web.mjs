// Web view demo for several repositories with search (spec sections 5.10 and 13).
//   node web.mjs <web-view-url> <output.webm>
import { chromium } from "playwright";
import { rename } from "node:fs/promises";
import { dirname } from "node:path";

const [url, output] = process.argv.slice(2);
const pause = (page, ms = 1500) => page.waitForTimeout(ms);
const REPOSITORIES = "github.com/JetBrains/MPS github.com/JetBrains/koog github.com/anthropics/skills";

const browser = await chromium.launch();
const context = await browser.newContext({
  viewport: { width: 1280, height: 800 },
  recordVideo: { dir: dirname(output), size: { width: 1280, height: 800 } },
});
const page = await context.newPage();
await page.goto(url);
await pause(page, 800);

// 1. Add three repositories at once.
await page.locator("#url").pressSequentially(REPOSITORIES, { delay: 25 });
await pause(page, 600);
await page.click("#scan-button");
await page.waitForSelector("body[data-state=idle]", { timeout: 180_000 });
await pause(page, 2000);

// 2. Search across all of them.
await page.locator("#filter").pressSequentially("test", { delay: 90 });
await pause(page, 2000);

// 3. Narrow the search to one repository with repo:.
await page.locator("#filter").press("Home");
await page.locator("#filter").pressSequentially("repo:mps ", { delay: 90 });
await pause(page, 2000);

// 4. Look at a skill, then jump to a similar skill in another repository.
await page.locator("#filter").fill("");
await page.locator("#filter").pressSequentially("repo:koog java", { delay: 60 });
await pause(page, 800);
await page.locator("[role=option]:visible", { hasText: "split-jvm-nonjvm" }).click();
await pause(page, 1500);
await page.locator("#similar").scrollIntoViewIfNeeded();
await pause(page, 1500);
await page.locator(".similar-row", { has: page.locator(".similar-repo") }).first().click();
await pause(page, 2500);

// 5. Remove a repository.
await page.locator(".chip", { hasText: "anthropics/skills" }).locator(".chip-remove").click();
await page.waitForSelector("body[data-state=idle]");
await pause(page, 2000);

const video = page.video();
await context.close();
await browser.close();
await rename(await video.path(), output);
