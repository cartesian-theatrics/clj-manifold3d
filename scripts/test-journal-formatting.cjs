// Regression: old example text survives deployments in IndexedDB, so updating
// the bundled examples alone doesn't repair the editor the user actually sees.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');
const {chromium} = require('playwright');

(async () => {
  const root = (await fs.readFile('target/journal-static-path', 'utf8')).trim();
  const mime = {'.html':'text/html', '.css':'text/css', '.js':'text/javascript', '.wasm':'application/wasm'};
  const server = http.createServer(async (req, res) => {
    try {
      const pathname = new URL(req.url, 'http://localhost').pathname;
      const file = path.resolve(root, '.' + pathname, pathname.endsWith('/') ? 'index.html' : '');
      assert.ok(file.startsWith(root + path.sep));
      res.writeHead(200, {'Content-Type':mime[path.extname(file)] || 'application/octet-stream'});
      res.end(await fs.readFile(file));
    } catch { res.end(); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({headless:true});
  try {
    const page = await browser.newPage();
    const errors = [], scripts = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', req => { if (req.url().includes('/js/')) scripts.push(req.url()); });
    await page.addInitScript(() => Object.defineProperty(window, 'journalBridge', {configurable:true, set(bridge) {
      const create = bridge.createCodeEditor;
      bridge.createCodeEditor = (el, options) => {
        const api = create(el, options); el.testEditor = api; return api;
      };
      Object.defineProperty(window, 'journalBridge', {value:bridge});
    }}));
    const ready = () => page.waitForFunction(() => document.getElementById('engine')?.textContent === 'Ready');
    const saved = () => page.waitForFunction(() => document.getElementById('save-status')?.textContent === 'Saved');
    const panel = page.locator('[data-block-id="journal-castle-night-camera"]');
    const source = () => panel.locator('.block-editor').evaluate(el => el.testEditor.getValue());
    const edit = async text => {
      await panel.locator('.cm-content').click();
      await page.keyboard.press('Control+a'); await page.keyboard.insertText(text); await saved();
    };
    await page.goto(process.env.JOURNAL_STATIC_URL || `http://127.0.0.1:${server.address().port}/journal/`);
    await ready();
    const formatted = await source();
    assert.match(formatted, /\(def camera-track\n/);
    assert.match(formatted, /\(defn assembly\n/);
    assert.ok(scripts.length >= 3 && scripts.every(url => /\?v=[a-f0-9]{16}$/.test(url)));
    const old = formatted.replace('(def camera-track', '(def\n camera-track')
      .replace('(defn assembly', '(defn\n assembly').replace('"A wish over the castle"', '"A wish over the castle",');
    await edit(old);
    assert.equal(await source(), old);
    await page.reload(); await ready(); await saved();
    assert.equal(await source(), formatted, 'Stored old layout is repaired in the visible CodeMirror editor');
    await page.reload(); await ready();
    assert.equal(await source(), formatted, 'Formatting repair persists in the same browser database');
    const edited = old + '\n;; My edited scene';
    await edit(edited);
    await page.reload(); await ready();
    assert.equal(await source(), edited, 'User-edited panels are not replaced');
    assert.deepEqual(await page.locator('[data-pane-id]').evaluateAll(els => els.map(el => el.dataset.paneId)),
      ['pane-architecture', 'pane-night']);
    assert.deepEqual(errors, []);
    console.log('PASS: versioned assets, visible formatting repair, durable reload, preserved edits and split layout');
  } finally {
    await browser.close(); await new Promise(resolve => server.close(resolve));
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
