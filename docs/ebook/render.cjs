// HTML 한 개를 PDF 로 출력한다.  사용법: node render.cjs 입력.html 출력.pdf
const { chromium } = require('playwright');
const { pathToFileURL } = require('node:url');
const path = require('node:path');

(async () => {
  const [, , input, output] = process.argv;
  if (!input || !output) { console.error('사용법: node render.cjs 입력.html 출력.pdf'); process.exit(1); }
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(pathToFileURL(path.resolve(input)).href, { waitUntil: 'load' });
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(async () => {
    await Promise.all([...document.images].map(img => img.complete ? null : new Promise(r => { img.onload = img.onerror = r; })));
  });
  await page.pdf({ path: output, preferCSSPageSize: true, printBackground: true, outline: true, tagged: true });
  await browser.close();
  console.log('PDF:', output);
})().catch(e => { console.error(e); process.exit(1); });
