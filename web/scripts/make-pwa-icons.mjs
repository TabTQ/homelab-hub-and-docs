// Renders the PNG app icons from the SVG sources (run manually when the icon changes; the PNGs are committed).
//   node scripts/make-pwa-icons.mjs
import { chromium } from "playwright-core";
import { readFileSync, writeFileSync } from "node:fs";
const dir = new URL("../src/internal/public/icons/", import.meta.url).pathname;
const jobs = [["icon.svg", "icon-192.png", 192, true], ["icon.svg", "icon-512.png", 512, true], ["icon-maskable.svg", "icon-maskable-512.png", 512, false]];
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM ?? "/usr/bin/chromium", args: ["--no-sandbox"] });
for (const [src, out, size, transparent] of jobs) {
  const page = await browser.newPage({ viewport: { width: size, height: size } });
  const svg = readFileSync(dir + src, "utf8");
  await page.setContent(`<html><body style="margin:0;background:transparent">${svg.replace("<svg ", `<svg width="${size}" height="${size}" `)}</body></html>`);
  writeFileSync(dir + out, await page.screenshot({ omitBackground: transparent }));
  await page.close();
}
await browser.close();
console.log("icons written to", dir);
