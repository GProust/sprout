// Draws the App Store "creative asset" for every language: the image at the
// top of the product page and behind the app in search results.
//
//     node ios/tools/creative_asset.mjs <captured-dir> <out-dir>
//
// <captured-dir> is a capture from ios-listing-screenshots.yml, one folder per
// App Store locale. Each language gets <out-dir>/<locale>.png at 5244 × 2950,
// the "universal" size, which App Store Connect crops to 21:9 for the header
// and to 3:2 for search results. Anything that has to be seen sits where the
// two crops overlap, a centred 4425 × 2247: the headline, and the tops of the
// phones, which are allowed to run off the bottom of the header.
//
// The phones are the capture itself, so the artwork shows the real app in the
// language around it, as Apple's guidance for search results asks. The one
// line of text is ios/fastlane/creative/<locale>/headline.txt, checked by
// check_listing.py.
//
// Playwright drives a browser to draw it. In CI it is the runner's own Chrome
// (CREATIVE_BROWSER_CHANNEL=chrome), so the type is the system's; elsewhere,
// PLAYWRIGHT_MODULE says where Playwright is installed and CREATIVE_FONT can
// name a .woff2 to stand in for a font the machine does not have.

import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const LOCALES = ["en-US", "fr-FR", "de-DE", "es-ES", "it", "pl", "pt-PT"];
const W = 5244;
const H = 2950;

// Left to right: the dashboard in the middle, the two logs a night is made of
// beside it, and the waits and the nappies at the edges.
// [screen, centre x, top, width, layer]
const PHONES = [
  ["07-medicines", 1080, 1240, 700, 1],
  ["02-feeding", 1820, 1060, 790, 2],
  ["01-home", 2622, 920, 880, 3],
  ["04-sleep", 3424, 1060, 790, 2],
  ["05-diaper", 4164, 1240, 700, 1],
];

const repo = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../..");
const [capturedDir, outDir] = process.argv.slice(2);
if (!capturedDir || !outDir) {
  console.error("usage: node ios/tools/creative_asset.mjs <captured-dir> <out-dir>");
  process.exit(2);
}

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? "playwright");

const escape = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
const font = process.env.CREATIVE_FONT
  ? `@font-face { font-family: Stand-in; font-weight: 700; src: url(${pathToFileURL(process.env.CREATIVE_FONT)}); }`
  : "";

function page(locale) {
  const headline = readFileSync(path.join(repo, "ios/fastlane/creative", locale, "headline.txt"), "utf8").trim();
  const phones = PHONES.map(([screen, cx, top, width, z]) => {
    const file = path.resolve(capturedDir, locale, `${screen}.png`);
    if (!existsSync(file)) throw new Error(`${locale}: ${screen}.png is not in the capture`);
    return `<div class="phone" style="left:${cx - width / 2}px; top:${top}px; width:${width}px; z-index:${z}">` +
      `<img src="${pathToFileURL(file)}"></div>`;
  }).join("\n");

  // The brand green of the icon, deepened so white type reads on it, with a
  // lighter wash where the headline sits.
  return `<!doctype html><html><head><meta charset="utf-8"><style>
${font}
html, body { margin: 0; width: ${W}px; height: ${H}px; overflow: hidden; }
body {
  background:
    radial-gradient(1900px 1300px at 50% 120%, rgba(255,255,255,0.16), transparent 70%),
    radial-gradient(2600px 1600px at 15% -10%, #5dbb8c 0%, transparent 60%),
    linear-gradient(160deg, #3f9c70 0%, #2e7a57 55%, #22603f 100%);
  color: #fff;
  font-family: Stand-in, -apple-system, BlinkMacSystemFont, "Helvetica Neue", sans-serif;
}
h1 {
  position: absolute; left: 0; right: 0; top: 540px; margin: 0 auto; width: 3900px;
  text-align: center; font-weight: 700; font-size: 172px; line-height: 1.12; letter-spacing: -3px;
  white-space: nowrap;
}
.phone {
  position: absolute; box-sizing: border-box; border-radius: 120px; padding: 22px;
  background: #0d1a14;
  box-shadow: 0 60px 140px rgba(0,0,0,0.35), 0 0 0 3px rgba(255,255,255,0.08);
}
.phone img { display: block; width: 100%; border-radius: 100px; }
</style></head><body>
<h1>${escape(headline)}</h1>
${phones}
</body></html>`;
}

mkdirSync(outDir, { recursive: true });
const pages = mkdtempSync(path.join(os.tmpdir(), "creative-"));
const browser = await chromium.launch(
  process.env.CREATIVE_BROWSER_CHANNEL ? { channel: process.env.CREATIVE_BROWSER_CHANNEL } : {}
);
try {
  const tab = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  for (const locale of LOCALES) {
    // From a file rather than setContent: a page with no origin may refuse to
    // load file:// images, and a header with five empty phones is no header.
    const html = path.join(pages, `${locale}.html`);
    writeFileSync(html, page(locale));
    await tab.goto(pathToFileURL(html).href, { waitUntil: "load" });
    const size = await tab.evaluate(async () => {
      await document.fonts.ready;
      if ([...document.images].some((i) => !i.complete || i.naturalWidth === 0)) {
        throw new Error("a screenshot did not load");
      }
      // One line, always: a long translation shrinks rather than wrapping
      // into the phones below it.
      const h1 = document.querySelector("h1");
      let px = 172;
      while (h1.scrollWidth > h1.clientWidth && px > 120) h1.style.fontSize = `${(px -= 2)}px`;
      if (h1.scrollWidth > h1.clientWidth) throw new Error("the headline does not fit on one line");
      return px;
    });
    await tab.screenshot({ path: path.resolve(outDir, `${locale}.png`), type: "png" });
    console.log(`${locale}: headline at ${size}px`);
  }
} finally {
  await browser.close();
}
