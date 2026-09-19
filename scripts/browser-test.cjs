const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('playwright');

(async () => {
  const root = path.resolve(__dirname, '../public');
  const server = http.createServer(async (request, response) => {
    try {
      const name = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
      const file = path.resolve(root, '.' + (name === '/' ? '/cljs-test.html' : name));
      if (!file.startsWith(root + path.sep)) { response.writeHead(403).end(); return; }
      const mime = {'.html': 'text/html', '.js': 'application/javascript', '.wasm': 'application/wasm', '.json': 'application/json'};
      response.setHeader('Content-Type', mime[path.extname(file)] || 'application/octet-stream');
      response.end(await fs.readFile(file));
    } catch (_) { response.writeHead(404).end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_PATH});
    const page = await browser.newPage();
    const errors = [];
    page.on('console', message => console.log(message.text()));
    page.on('pageerror', error => errors.push(String(error)));
    await page.goto('http://127.0.0.1:' + server.address().port);
    await page.waitForFunction(() => globalThis.CLJS_TEST_RESULT !== undefined, null, {timeout: 120000});
    const result = await page.evaluate(() => globalThis.CLJS_TEST_RESULT);
    console.log(JSON.stringify(result));
    if (!result.success || !result.test || errors.length) {
      throw new Error('Browser tests failed: ' + JSON.stringify({result, errors}));
    }
  } finally {
    if (browser) await browser.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
