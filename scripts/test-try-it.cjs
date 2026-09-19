const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {chromium} = require('playwright');
const {createServer} = require('./serve-try-it.cjs');

(async () => {
  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_PATH,
      args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']});
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}, acceptDownloads: true});
    const errors = [];
    page.on('pageerror', error => { errors.push(String(error)); console.error(error); });
    page.on('console', message => { if (message.type() === 'error') console.error(message.text()); });
    const url = `http://127.0.0.1:${server.address().port}/`;
    const ready = async () => {
      await page.waitForFunction(() => ['ready', 'error'].includes(document.getElementById('model-state').dataset.state), null, {timeout: 45000});
      assert.equal(await page.locator('#model-state').getAttribute('data-state'), 'ready', await page.locator('#output').textContent());
    };
    const input = page.getByRole('textbox', {name: 'ClojureScript code'});
    const codeText = () => page.evaluate(() => window.manifoldEditor.getValue());
    const edit = async code => {
      await input.click();
      await input.press('ControlOrMeta+a');
      await input.press('Backspace');
      if (code) await page.keyboard.insertText(code);
      assert.equal(await codeText(), code);
    };
    const run = async (code, shortcut = false) => {
      await page.locator('#run').waitFor({state: 'visible'});
      await page.waitForFunction(() => !document.getElementById('run').disabled);
      await edit(code);
      if (shortcut) await input.press('ControlOrMeta+Enter');
      else await page.locator('#run').click();
    };
    await page.goto(url);
    await ready();
    assert.equal(await page.locator('#example').inputValue(), 'loft');
    const loft = await page.evaluate(() => window.manifoldViewer.inspect());
    assert.ok(loft.meshes.length && loft.meshes[0].vertices > 24, 'Real loft geometry reached WebGL');
    await fs.mkdir('target', {recursive: true});
    await page.screenshot({path: 'target/try-it-desktop.png', fullPage: true});
    console.log('PASS: default loft renders in the optimized app');

    await edit(';; syntax check\n(let [n 42] {:answer n :label "flag" :enabled true})');
    const colors = [];
    for (const token of ['comment', 'keyword', 'atom', 'number', 'string']) {
      const span = page.locator(`#code .tok-${token}`).first();
      await span.waitFor();
      colors.push(await span.evaluate(node => getComputedStyle(node).color));
    }
    assert.equal(new Set(colors).size, colors.length, 'Distinct syntax token colors are applied');
    await edit('');
    for (const [open, close] of [['(', ')'], ['[', ']'], ['{', '}'], ['"', '"']]) {
      await page.keyboard.type(open);
      assert.equal(await codeText(), open + close, `Automatically closes ${open}`);
      await page.keyboard.type(close);
      assert.equal(await codeText(), open + close, `Typing ${close} skips the existing closer`);
      await input.press('ArrowLeft');
      await input.press('Backspace');
      assert.equal(await codeText(), '', 'Backspace removes an empty pair');
    }
    await page.keyboard.type("'cube");
    assert.equal(await codeText(), "'cube", 'Clojure reader quote is not auto-paired');
    await edit('');
    await page.keyboard.type('(let [x 1] (+ x 2))');
    assert.equal(await codeText(), '(let [x 1] (+ x 2))', 'Nested forms need no duplicate closers');
    await edit('');
    await page.keyboard.type('(let [x 1]');
    await input.press('Enter');
    assert.equal(await codeText(), '(let [x 1]\n  )', 'Clojure forms auto-indent on Enter');
    await input.press('Tab');
    assert.equal(await codeText(), '(let [x 1]\n    )', 'Tab indents with spaces');
    console.log('PASS: ClojureScript highlighting, bracket pairing/skipping/deletion, reader quotes, and indentation');

    await run('(println "hello from CLJS")\n(m/cube 4 5 6)', true);
    await ready();
    assert.match(await page.locator('#stats').textContent(), /120 units³/);
    assert.match(await page.locator('#output').textContent(), /hello from CLJS/);
    const cube = await page.evaluate(() => window.manifoldViewer.inspect());
    const downloaded = page.waitForEvent('download');
    await page.locator('#download').click();
    const download = await downloaded;
    const bytes = await fs.readFile(await download.path());
    assert.equal(bytes.readUInt32LE(0), 0x46546c67);
    assert.equal(bytes.readUInt32LE(8), bytes.length);
    const {NodeIO} = await import('@gltf-transform/core');
    const document = await new NodeIO().readBinary(bytes);
    assert.equal(document.getRoot().listMeshes().length, 1);
    console.log('PASS: edited CLJS, Ctrl/Cmd+Enter, captured output, volume, and downloadable GLB');

    for (const invalid of ['(let [broken)', '(js/alert "not exposed")', '42']) {
      await run(invalid);
      await page.waitForFunction(() => document.getElementById('model-state').dataset.state === 'error');
      assert.deepEqual(await page.evaluate(() => window.manifoldViewer.inspect()), cube);
    }
    console.log('PASS: syntax/interop/result errors preserve the last good model');

    await run('(loop [] (recur))');
    await page.waitForFunction(() => document.getElementById('engine-status').textContent.includes('evaluating'));
    await page.locator('#stop').click();
    await page.waitForFunction(() => !document.getElementById('run').disabled);
    assert.match(await page.locator('#output').textContent(), /Stopped/);
    await run('(m/cube 2 3 4)');
    await ready();
    assert.match(await page.locator('#stats').textContent(), /24 units³/);
    console.log('PASS: infinite loop cancellation and worker recovery');

    for (const id of ['boolean', 'depth']) {
      await page.locator('#example').selectOption(id);
      await ready();
      assert.ok((await page.evaluate(() => window.manifoldViewer.inspect())).meshes.length);
    }
    assert.match(await codeText(), /m\/color/);
    assert.match(await codeText(), /texture\/bake/);
    assert.match(await codeText(), /:depth-boundary :step/);
    const flagCode = await codeText();
    assert.match(flagCode, /m\/model/);
    assert.match(flagCode, /m\/texture image/);
    assert.match(flagCode, /wave-columns/);
    assert.match(flagCode, /:depth-map flag-depth/);
    assert.doesNotMatch(flagCode, /\{:geometry surface/);

    // A saved flag from before the cloth wave may still contain custom sphere
    // settings. Upgrade only the flat depth section and retain those settings.
    const bindingsStart = flagCode.indexOf('      ;; A depth grid');
    const surfaceStart = flagCode.indexOf('      surface (->');
    const legacyFlagCode = flagCode.slice(0, bindingsStart) + flagCode.slice(surfaceStart)
      .replace(':depth-map flag-depth', ':depth-map [[1 1] [1 1]]')
      .replace(':depth-scale 0.75', ':depth-scale 0.65')
      .replace('(m/sphere 12 128)', '(m/sphere 15 50)');
    await page.evaluate(code => localStorage.setItem('manifold.try-it.code.depth', code), legacyFlagCode);
    await page.reload();
    await ready();
    const upgradedFlagCode = await codeText();
    assert.match(upgradedFlagCode, /wave-columns/);
    assert.match(upgradedFlagCode, /:depth-map flag-depth/);
    assert.match(upgradedFlagCode, /\(m\/sphere 15 50\)/);
    await page.locator('#reset-code').click();
    await ready();
    assert.equal(await codeText(), flagCode, 'Flat saved flags upgrade while preserving custom geometry until reset');

    await page.evaluate(code => localStorage.setItem('manifold.try-it.code.depth', code), flagCode.replace(':size [13.3 7]', ':size [17.1 9]'));
    await page.reload();
    await ready();
    assert.equal(await codeText(), flagCode, 'The oversized saved draft upgrades to the working flag patch');
    const flagDownload = page.waitForEvent('download');
    await page.locator('#download').click();
    const flagBytes = await fs.readFile(await (await flagDownload).path());
    const flagDocument = await new NodeIO().readBinary(flagBytes);
    assert.match(Buffer.from(flagBytes).toString(), /manifold::Model/, 'Preview/export uses the native Model writer');
    const flagPrimitives = flagDocument.getRoot().listMeshes()[0].listPrimitives();
    assert.equal(flagPrimitives.length, 2, 'Decal and bare sphere use separate materials in one mesh');
    const flagMesh = flagPrimitives.find(p => p.getMaterial().getBaseColorTexture());
    const uv = flagMesh.getAttribute('TEXCOORD_0');
    assert.ok(uv && uv.getCount() > 1000, 'Native surface walk exports real UV vertices');
    const radii = flagPrimitives.flatMap(p => {
      const positions = p.getAttribute('POSITION').getArray();
      return Array.from({length: positions.length / 3}, (_, i) => Math.hypot(...positions.subarray(i * 3, i * 3 + 3)));
    });
    assert.ok(Math.max(...radii) > 13.0 && Math.max(...radii) < 13.7, 'Patch is displaced outward, not just painted');
    assert.ok(radii.some(r => Math.abs(r - 12) < 0.03), 'Unmapped sphere keeps its original radius');
    const flagPositions = flagMesh.getAttribute('POSITION').getArray();
    const flagRadiusValues = Array.from({length: flagPositions.length / 3}, (_, i) =>
      Math.hypot(...flagPositions.subarray(i * 3, i * 3 + 3)));
    assert.ok(Math.max(...flagRadiusValues) - Math.min(...flagRadiusValues) > 0.75,
      'Mapped flag has visible depth variation across its wave');
    const texture = flagMesh.getMaterial().getBaseColorTexture();
    assert.ok(texture, 'Texture is used by the exported material');
    const exportedImage = require('fast-png').decode(texture.getImage(), {checkCrc: true});
    assert.equal(exportedImage.width, 950, 'Single-layer export retains source resolution, not a per-triangle atlas');
    assert.equal(exportedImage.height, 500);
    const channels = {red: 0, white: 0, blue: 0};
    for (let i = 0; i < exportedImage.data.length; i += 4) {
      const [r, g, b, a] = exportedImage.data.subarray(i, i + 4);
      assert.equal(a, 255, 'Opaque source-over model exports an opaque image');
      if (r > 150 && g < 65 && b < 80) channels.red++;
      if (r > 245 && g > 245 && b > 245) channels.white++;
      if (b > 100 && b > r * 2 && b > g) channels.blue++;
    }
    assert.ok(Object.values(channels).every(n => n > 500), 'Native export retains the flag colors');
    assert.equal(flagMesh.getMaterial().getAlphaMode(), 'OPAQUE');
    await fs.writeFile('target/try-it-flag.glb', flagBytes);
    await fs.writeFile('target/try-it-flag.png', texture.getImage());
    await page.screenshot({path: 'target/try-it-flag-viewer.png', fullPage: true});

    // Verify that export preserved the complete library-drawn source image.
    // Keep the exact stripe/star assertions, not just color counts.
    const sourceCode = flagCode.slice(0, flagCode.indexOf('      surface (->')) +
      '] {:geometry (texture/planar-uv-native (m/cube 1 1 1)) :texture image})';
    await run(sourceCode); await ready();
    const sourceDownload = page.waitForEvent('download');
    await page.locator('#download').click();
    const sourceDoc = await new NodeIO().readBinary(await fs.readFile(await (await sourceDownload).path()));
    const sourceImage = sourceDoc.getRoot().listTextures()[0].getImage();
    const png = require('fast-png').decode(sourceImage, {checkCrc: true});
    assert.equal(png.width, 950);
    assert.equal(png.height, 500);
    assert.deepEqual(exportedImage.data, png.data, 'Every source texel survives direct Model export unchanged');
    const pixel = (u, v) => {
      const start = (Math.floor(v * png.height) * png.width + Math.floor(u * png.width)) * 4;
      return Array.from(png.data.subarray(start, start + 3));
    };
    for (let row = 0; row < 13; row++) {
      const [r, g, b] = pixel(0.8, 0.1 + 0.8 * (row + 0.5) / 13);
      assert.ok(row % 2 ? r > 245 && g > 245 && b > 245 : r > 150 && g < 65 && b < 80, `Stripe ${row + 1} has the correct color`);
    }
    const [r, g, b] = pixel(0.11, 0.11);
    assert.ok(b > 100 && b > r * 2 && b > g, 'Canton is blue at the upper left');
    // Count disconnected white stars in the blue canton, directly in the baked image.
    const left = Math.ceil(0.1 * png.width), right = Math.floor(0.42 * png.width);
    const top = Math.ceil(0.1 * png.height), bottom = Math.floor((0.1 + 0.8 * 7 / 13) * png.height);
    const white = new Set();
    for (let y = top; y < bottom; y++) for (let x = left; x < right; x++) {
      const i = y * png.width + x;
      if ([0, 1, 2].every(c => png.data[i * 4 + c] > 245)) white.add(i);
    }
    let stars = 0;
    while (white.size) {
      const seed = white.values().next().value;
      const todo = [seed]; white.delete(seed); stars++;
      while (todo.length) {
        const i = todo.pop();
        for (const dy of [-1, 0, 1]) for (const dx of [-1, 0, 1]) {
          const neighbor = i + dy * png.width + dx;
          if (white.delete(neighbor)) todo.push(neighbor);
        }
      }
    }
    assert.equal(stars, 50, 'Actual fifty white stars are present, not a placeholder image');
    await fs.writeFile('target/try-it-flag-source.png', sourceImage);
    await run(flagCode); await ready();
    console.log('PASS: library-drawn 13-stripe/50-star flag, embedded UV texture, and stepped sphere geometry');
    await page.locator('#wireframe').click();
    assert.equal(await page.evaluate(() => window.manifoldViewer.inspect().wireframe), true);
    await page.locator('#wireframe').click();
    console.log('PASS: boolean, surface depth, and wireframe');

    await page.locator('#example').selectOption('animation');
    await ready();
    const pivotCode = await codeText();
    for (const implementation of ['m/scene', ':children [:arm]', ':translation [(/ length 2) 0 0]', 'animation/keyframes', ':node :arm-pivot']) {
      assert.ok(pivotCode.includes(implementation), `Editable pivot implementation includes ${implementation}`);
    }
    assert.ok(!pivotCode.includes('pivot-arm-scene'), 'Demo constructs its own scene, not a helper entrypoint');
    const before = await page.evaluate(() => window.manifoldViewer.inspect());
    assert.equal(before.animations, 1);
    assert.equal(before.meshes.length, 2, 'Base and arm are distinct meshes');
    await page.waitForFunction(matrix => window.manifoldViewer.inspect().meshes.some((mesh, i) => mesh.matrix.some((v, j) => Math.abs(v - matrix[i].matrix[j]) > 0.05)), before.meshes);
    await page.locator('#play').click();
    const paused = await page.evaluate(() => window.manifoldViewer.inspect());
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.deepEqual(await page.evaluate(() => window.manifoldViewer.inspect()), paused);
    console.log('PASS: exported GLB animation really pivots and pauses');

    // Users who loaded the old stock demo should see the implementation on refresh.
    const previousPivotCode = "(require '[clj-manifold3d.animation :as animation])\n\n;; Return a scene to preview its animation.\n(animation/pivot-arm-scene\n  {:length 35\n   :width 6\n   :thickness 4\n   :base-radius 8\n   :base-height 5})";
    await page.evaluate(code => localStorage.setItem('manifold.try-it.code.animation', code), previousPivotCode);
    await page.reload();
    await ready();
    assert.equal(await codeText(), pivotCode, 'Unchanged old default is upgraded');
    assert.equal(await page.evaluate(() => localStorage.getItem('manifold.try-it.code.animation')), pivotCode);
    const customized = previousPivotCode.replace(':length 35', ':length 41');
    await edit(customized);
    await page.reload();
    await ready();
    assert.equal(await codeText(), customized, 'Customized saved code is preserved');
    await page.locator('#reset-code').click();
    await ready();
    assert.equal(await codeText(), pivotCode, 'Reset restores the full pivot implementation');
    await input.click();
    await input.press('ControlOrMeta+End');
    await page.keyboard.insertText('\n;; my pivot');
    await input.press('ControlOrMeta+z');
    assert.equal(await codeText(), pivotCode, 'Undo restores the current example');
    await input.press('ControlOrMeta+z');
    assert.equal(await codeText(), pivotCode, 'Undo cannot leak code from before Reset');
    console.log('PASS: old demo migration, custom edit preservation, reset, and isolated undo history');

    await page.locator('#example').selectOption('loft');
    await page.locator('#reset-code').click();
    await ready();
    await page.reload();
    await ready();
    assert.match(await codeText(), /m\/loft/);
    await page.setViewportSize({width: 390, height: 844});
    await page.screenshot({path: 'target/try-it-mobile.png', fullPage: true});
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'No mobile horizontal overflow');
    const hidden = await fetch(url + 'deps.edn');
    assert.equal(hidden.status, 404);
    const traversal = await fetch(url + 'vendor/three/%2e%2e%2f%2e%2e%2fpackage.json');
    assert.ok([403, 404].includes(traversal.status));
    assert.deepEqual(errors, []);
    console.log('PASS: persistence, responsive layout, and static-server path restrictions');
    console.log('All Try it! browser checks passed. Screenshots: target/try-it-{desktop,mobile}.png');
  } catch (error) {
    if (browser) {
      const page = browser.contexts()[0]?.pages()[0];
      if (page) {
        console.error('Page output:', await page.locator('#output').textContent());
        await page.screenshot({path: 'target/try-it-failure.png', fullPage: true});
      }
    }
    throw error;
  } finally {
    if (browser) await browser.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
