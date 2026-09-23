// Publish only this allowlist. Never copy local journals, Datahike, configuration,
// source maps, credentials, or the other applications under public/.
const fs = require('node:fs/promises');
const path = require('node:path');
const {execFileSync} = require('node:child_process');

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
  await fs.writeFile(path.join(output, '.nojekyll'), '');
  await fs.writeFile(path.join(output, 'index.html'), '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><meta http-equiv="refresh" content="0;url=./journal/"><title>Modeling Journal</title><a href="./journal/">Open the Modeling Journal</a></html>\n');
  await fs.writeFile(path.join(output, 'build.json'), JSON.stringify({
    source: execFileSync('git', ['rev-parse', 'HEAD'], {encoding:'utf8'}).trim(),
    built: new Date().toISOString()
  }, null, 2) + '\n');
  await fs.writeFile('target/journal-static-path', output + '\n');
  console.log(`Static journal: ${output}`);
})().catch(error => { console.error(error); process.exitCode = 1; });
