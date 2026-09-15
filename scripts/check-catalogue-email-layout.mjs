/** Browser regression for the actual renderer fixture emitted by OrisoEmailRendererTest.
 * Run its longContactUsernameRemainsIntactInActualEmailChangedTemplate test with -Demail.layout.browserFixture=/absolute/fixture.html,
 * then: PLAYWRIGHT_MODULE=/path/to/playwright/index.mjs node scripts/check-catalogue-email-layout.mjs fixture.html output-dir
 * Uses the existing E2E browser installation; does not add a production dependency.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const [fixture, output] = process.argv.slice(2);
assert(fixture && output, 'Provide actual renderer HTML fixture and output directory');
await fs.mkdir(output, { recursive: true });
const html = await fs.readFile(fixture, 'utf8');
const browser = await chromium.launch();
const results = [];
try {
  for (const width of [390, 820, 1440]) {
    const page = await browser.newPage({ viewport: { width, height: 1000 } });
    await page.route('**/*', route => route.abort());
    await page.setContent(html);
    const result = await page.evaluate(() => {
      const bounds = element => {
        const r = element.getBoundingClientRect();
        return { left: r.left, right: r.right, width: r.width };
      };
      const card = document.querySelector('.wrap');
      const textElements = [...document.querySelectorAll('.wrap h1, .wrap td, .wrap a')].filter(element => element.childElementCount === 0);
      return {
        viewport: innerWidth,
        bodyText: document.body.textContent,
        documentWidth: document.documentElement.scrollWidth,
        card: bounds(card),
        text: textElements.map(element => {
          const range = document.createRange();
          range.selectNodeContents(element);
          const style = getComputedStyle(element);
          return {
            tag: element.tagName,
            text: element.textContent,
            href: element.getAttribute('href'),
            rects: [...range.getClientRects()].map(r => ({ left: r.left, right: r.right })),
            overflow: style.overflow,
            textOverflow: style.textOverflow,
            fontSize: style.fontSize,
            lineHeight: style.lineHeight,
          };
        }),
      };
    });
    result.failures = [];
    if (result.documentWidth > width) result.failures.push('Document overflows viewport');
    if (result.card.left < 0 || result.card.right > width || result.card.width > 601) result.failures.push('Card exceeds viewport or desktop maximum');
    if (!result.text.length) result.failures.push('Missing rendered fixture content');
    for (const item of result.text) {
      if (item.tag === 'H1' && parseFloat(item.lineHeight) < parseFloat(item.fontSize)) result.failures.push('Heading lines overlap');
      if (item.rects.some(r => r.left < result.card.left - 1 || r.right > result.card.right + 1)) result.failures.push(`${item.tag} text escapes card`);
      if (['hidden', 'clip'].includes(item.overflow) || item.textOverflow === 'ellipsis') result.failures.push(`${item.tag} clips content`);
    }
    if (!result.bodyText.includes(`${'alexander'.repeat(7)}@example.org`)) result.failures.push('Contact username changed');
    results.push(result);
    await page.screenshot({ path: path.join(output, `layout-${width}.png`), fullPage: true });
    await page.close();
  }
} finally {
  await browser.close();
}
await fs.writeFile(path.join(output, 'results.json'), JSON.stringify(results, null, 2));
console.log(JSON.stringify(results.map(({ viewport, documentWidth, card, failures }) => ({ viewport, documentWidth, card, failures }))));
assert(results.every(result => result.failures.length === 0), 'Email layout overflows or clips content; see results.json');
