// Publish only this allowlist. Never copy local journals, Datahike, configuration,
// source maps, credentials, or the other applications under public/.
const fs = require('node:fs/promises');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const {createHash} = require('node:crypto');

(async () => {
  const files = [
    'journal/index.html', 'journal/style.css',
    'journal/js/editor.js', 'journal/js/viewer.js', 'journal/js/main.js',
    'journal/worker/worker.js', 'wasm/manifold.js', 'wasm/manifold.wasm'
  ];
  // A fresh directory prevents old builds or accidental files entering a release.
  await fs.mkdir('target', {recursive: true});
  const output = await fs.mkdtemp(path.resolve('target/journal-static-'));
  for (const file of files) {
    const bytes = await fs.readFile(path.join('public', file));
    const destination = path.join(output, file);
    await fs.mkdir(path.dirname(destination), {recursive: true});
    await fs.writeFile(destination, file === 'journal/index.html'
      ? bytes.toString().replace('<html lang="en">', '<html lang="en" data-journal-mode="static">')
      : bytes);
  }
  // Saved journals are independent of asset versions. A new HTML response must
  // load the matching example catalog even when the browser has cached old JS.
  const index = path.join(output, 'journal/index.html');
  let html = await fs.readFile(index, 'utf8');
  for (const file of ['style.css', 'js/editor.js', 'js/viewer.js', 'js/main.js']) {
    const digest = createHash('sha256').update(await fs.readFile(path.join(output, 'journal', file))).digest('hex').slice(0, 16);
    html = html.replaceAll(`./${file}`, `./${file}?v=${digest}`);
  }
  // Version the worker and its WASM too, without changing browser storage keys.
  const runtimeHash = createHash('sha256');
  for (const file of ['journal/worker/worker.js', 'wasm/manifold.js', 'wasm/manifold.wasm']) {
    runtimeHash.update(await fs.readFile(path.join(output, file)));
  }
  html = html.replace('data-journal-mode="static"', `data-journal-mode="static" data-journal-build="${runtimeHash.digest('hex').slice(0, 16)}"`);
  await fs.writeFile(index, html);
  await fs.writeFile(path.join(output, '.nojekyll'), '');
  await fs.writeFile(path.join(output, 'index.html'), '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><meta http-equiv="refresh" content="0;url=./journal/"><title>Modeling Journal</title><a href="./journal/">Open the Modeling Journal</a></html>\n');
  await fs.writeFile(path.join(output, 'build.json'), JSON.stringify({
    source: execFileSync('git', ['rev-parse', 'HEAD'], {encoding:'utf8'}).trim(),
    built: new Date().toISOString()
  }, null, 2) + '\n');
  await fs.writeFile('target/journal-static-path', output + '\n');
  console.log(`Static journal: ${output}`);
})().catch(error => { console.error(error); process.exitCode = 1; });
