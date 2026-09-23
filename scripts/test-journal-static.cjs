// Exercise the advanced build under a Pages-like subpath, with no API server.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const http = require('node:http');
const {chromium} = require('playwright');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
  const root = (await fs.readFile('target/journal-static-path', 'utf8')).trim();
  const prefix = '/project/clj-manifold3d/';
  const mime = {'.html':'text/html', '.css':'text/css', '.js':'text/javascript', '.wasm':'application/wasm'};
  const server = http.createServer(async (req, res) => {
    try {
      const pathname = new URL(req.url, 'http://localhost').pathname;
      if (!pathname.startsWith(prefix)) throw Error('Outside deployment');
      const file = path.resolve(root, '.' + pathname.slice(prefix.length - 1), pathname.endsWith('/') ? 'index.html' : '');
      if (!file.startsWith(root + path.sep)) throw Error('Outside deployment');
      const bytes = await fs.readFile(file);
      res.writeHead(200, {'Content-Type': mime[path.extname(file)] || 'application/octet-stream'}).end(bytes);
    } catch { res.writeHead(404).end('Not found'); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = process.env.JOURNAL_STATIC_URL || `http://127.0.0.1:${server.address().port}${prefix}journal/`;
  const base = new URL('../', url).href;
  let browser;
  try {
    browser = await chromium.launch({headless:true, args:['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
    const context = await browser.newContext({viewport:{width:1440,height:1000}, acceptDownloads:true});
    const requests = [], errors = [];
    context.on('request', req => {
      if (req.url().startsWith('http') && (!req.url().startsWith(base) || req.url().includes('/api/'))) requests.push(req.url());
    });
    await context.addInitScript(() => Object.defineProperty(window, 'journalBridge', {configurable:true, set(bridge) {
      const create = bridge.createViewer;
      bridge.createViewer = (el, ...args) => { const api = create(el, ...args); el.testViewer = api; return api; };
      const code = bridge.createCodeEditor;
      bridge.createCodeEditor = (el, options) => { const api = code(el, options); el.testEditor = api; return api; };
      Object.defineProperty(window, 'journalBridge', {value:bridge});
    }}));
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    const ready = p => p.waitForFunction(() => document.getElementById('engine')?.textContent === 'Ready', null, {timeout:90000});
    const saved = p => p.waitForFunction(() => document.getElementById('save-status').textContent === 'Saved', null, {timeout:15000});
    const codeSource = p => p.locator('[data-kind="code"] .block-editor').first().evaluate(el => el.testEditor.getValue());
    const evaluate = async () => {
      await page.getByRole('button', {name:'Evaluate document · Ctrl/Cmd+Alt+Enter', exact:true}).click();
      await ready(page);
      assert.equal(await page.locator('.block-result[data-status="error"]').count(), 0,
        (await page.locator('.block-result[data-status="error"]').allTextContents()).join('\n'));
      const model = page.locator('.solid-preview').last();
      await model.scrollIntoViewIfNeeded();
      await page.waitForFunction(() => [...document.querySelectorAll('.solid-preview')].some(el => el.dataset.loaded === 'true'), null, {timeout:120000});
      return model;
    };
    await page.goto(url); await ready(page);
    assert.deepEqual((await page.locator('#document-list [data-document]').evaluateAll(els => els.map(el => el.dataset.document))).sort(),
      ['journal.castle-architecture', 'journal.castle-night', 'journal.flag-uv']);
    assert.equal(await page.locator('.codex-model-controls').isVisible(), false);
    assert.equal(await page.locator('#browser-storage').isVisible(), true);
    assert.equal(await page.getByRole('button', {name:/Send prose to Codex/}).count(), 0);

    await page.locator('[data-document="journal.flag-uv"]').click();
    const flag = await evaluate();
    assert.ok((await flag.evaluate(el => el.testViewer.inspect())).meshCount > 0);
    console.log('PASS: curated examples; real UV flag evaluated and rendered without a backend');
    await page.locator('[data-document="journal.castle-night"]').click();
    const start = Date.now(), castle = await evaluate();
    const a = await castle.evaluate(el => el.testViewer.inspect());
    await delay(350);
    const b = await castle.evaluate(el => el.testViewer.inspect());
    assert.equal(a.authoredLights, 6); assert.equal(a.authoredCamera, true); assert.equal(a.clips, 1);
    assert.ok(a.meshCount > 600); assert.notDeepEqual(a.positions, b.positions);
    const download = page.waitForEvent('download');
    await page.getByRole('button', {name:'Download this result as GLB · Ctrl+Alt+D', exact:true}).click();
    const bytes = await fs.readFile(await (await download).path());
    const glb = JSON.parse(bytes.subarray(20, 20 + bytes.readUInt32LE(12)));
    assert.ok(glb.images.length >= 2); assert.equal(glb.extensions.KHR_lights_punctual.lights.length, 6);
    assert.ok(glb.animations[0].channels.length > 800);
    await page.screenshot({path:'target/journal-static.png'});
    console.log(`PASS: complete castle imports architecture, renders textures/lights/animation, exports GLB (${((Date.now()-start)/1000).toFixed(1)}s)`);

    // Edit through CodeMirror (not storage internals), then exercise durable state.
    await page.locator('[data-document="journal.flag-uv"]').click();
    const edit = async (p, marker) => {
      const code = p.locator('[data-kind="code"] .cm-content').first();
      await code.click(); await p.keyboard.press('Control+End'); await p.keyboard.insertText(`\n;; ${marker}`);
    };
    await edit(page, 'static-persistence-marker'); await saved(page);
    await page.getByRole('button', {name:'Split document vertically · Ctrl+Alt+S', exact:true}).click();
    await saved(page); await page.reload(); await ready(page);
    assert.equal(await page.locator('[data-pane-id]').count(), 2);
    assert.ok((await codeSource(page)).includes('static-persistence-marker'));
    // Reduce to a single pane before evaluation/editor assertions below.
    await page.getByRole('button', {name:'Close pane · Ctrl+Alt+W', exact:true}).last().click();
    await saved(page);

    const backupDownload = page.waitForEvent('download');
    await page.locator('#export-backup').click();
    const backupPath = await (await backupDownload).path();
    const backup = await fs.readFile(backupPath, 'utf8');
    assert.ok(backup.includes(':format :modeling-journal'));
    assert.ok(backup.includes('static-persistence-marker'));
    await edit(page, 'removed-by-import'); await saved(page);
    page.once('dialog', dialog => dialog.accept());
    await page.locator('#backup-file').setInputFiles(backupPath);
    await page.waitForFunction(() => !document.querySelector('[data-kind="code"] .block-editor').testEditor.getValue().includes('removed-by-import'));
    await saved(page); await page.reload(); await ready(page);
    assert.ok((await codeSource(page)).includes('static-persistence-marker'));
    assert.ok(!(await codeSource(page)).includes('removed-by-import'));
    console.log('PASS: code edits and vertical splits survive reload; backup restores edited panels');

    const other = await context.newPage(); await other.goto(url); await ready(other);
    await edit(page, 'newer-tab-edit'); await saved(page);
    await edit(other, 'stale-tab-edit');
    await other.waitForFunction(() => document.getElementById('save-status').textContent.includes('changed in another tab'));
    await page.reload(); await ready(page);
    const source = await codeSource(page);
    assert.ok(source.includes('newer-tab-edit')); assert.ok(!source.includes('stale-tab-edit'));
    await other.close();
    await page.locator('#new-document').click();
    await page.locator('#document-name').fill('workshop.static');
    await page.locator('#document-title').fill('Browser journal');
    await page.locator('#document-form button[type="submit"]').click();
    await saved(page); await evaluate(); await page.reload(); await ready(page);
    assert.equal(await page.locator('[data-document="workshop.static"]').count(), 1);
    assert.deepEqual(requests, [], 'No API, external CDN, or root-relative asset requests');
    assert.deepEqual(errors, []);
    console.log('PASS: concurrent-tab conflicts protect edits; new namespace persists and evaluates; zero API requests');
  } finally {
    await browser?.close(); await new Promise(resolve => server.close(resolve));
  }
})().catch(error => {console.error(error); process.exitCode = 1;});
