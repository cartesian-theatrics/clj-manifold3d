// Tests the production (Closure advanced) build, using an isolated Datahike store.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {spawn} = require('node:child_process');
const net = require('node:net');
const {chromium} = require('playwright');

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(f, timeout=60000) {
  const end=Date.now()+timeout;
  while(Date.now()<end) { if(await f()) return; await delay(100); }
  throw Error('Timed out waiting for condition');
}
(async()=>{
  const data=await fs.mkdtemp(path.join(os.tmpdir(),'manifold-journal-test-'));
  const socket=net.createServer();await new Promise(r=>socket.listen(0,'127.0.0.1',r));
  const port=socket.address().port;await new Promise(r=>socket.close(r));
  let server, browser, logs='';
  const url=`http://127.0.0.1:${port}`;
  const start=async()=>{
    server=spawn('clojure',['-M:journal-server'],{env:{...process.env,JOURNAL_DATA:data,JOURNAL_PORT:String(port),JOURNAL_CODEX_BIN:path.resolve('scripts/fixtures/codex-fixture.cjs')},stdio:['ignore','pipe','pipe']});
    server.stdout.on('data',x=>logs+=x);server.stderr.on('data',x=>logs+=x);
    await until(async()=>{if(server.exitCode!==null)throw Error(logs);try{return (await fetch(url+'/api/state')).ok;}catch{return false;}},90000);
  };
  const stop=async()=>{if(server&&server.exitCode===null){const done=new Promise(r=>server.once('exit',r));server.kill('SIGTERM');await done;}};
  try {
    await start();
    browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
    const page=await browser.newPage({viewport:{width:1480,height:1100},acceptDownloads:true});
    const errors=[];page.on('pageerror',e=>{errors.push(e.message);console.error('Browser error:',e.message);});
    await page.addInitScript(()=>{
      // Instrument resource bridges, not application state; the production app
      // still reads/writes facts exclusively through DataScript transactions.
      Object.defineProperty(window,'journalBridge',{configurable:true,set(bridge){
        window.testEditors=[];window.testViewers=[];
        const editor=bridge.createCodeEditor,viewer=bridge.createViewer;
        bridge.createCodeEditor=(el,opts)=>{const api=editor(el,opts);window.testEditors.push({el,api});return api;};
        bridge.createViewer=(el,url)=>{const api=viewer(el,url);window.testViewers.push({el,api});return api;};
        Object.defineProperty(window,'journalBridge',{value:bridge});
      }});
    });
    await page.goto(url+'/journal/');
    const ready=()=>page.waitForFunction(()=>document.getElementById('engine').textContent==='Ready',null,{timeout:45000});
    const saved=()=>page.waitForFunction(()=>document.getElementById('save-status').textContent==='Saved');
    await ready();
    const runPage=async()=>{await page.getByRole('button',{name:'Evaluate document · Ctrl/Cmd+Alt+Enter',exact:true}).first().click();await ready();};
    await runPage();
    assert.match((await page.locator('.result-label').allTextContents()).join(' '),/volume 96.000.*volume 89.860.*Cross-section/);
    await page.locator('.section-preview svg').scrollIntoViewIfNeeded();
    await page.locator('.solid-preview').first().scrollIntoViewIfNeeded();
    await page.waitForFunction(()=>document.querySelector('.solid-preview')?.dataset.loaded==='true');
    const downloadPromise=page.waitForEvent('download');
    await page.getByRole('button',{name:'Download this result as GLB · Ctrl+Alt+D',exact:true}).first().click();
    const bytes=await fs.readFile(await (await downloadPromise).path());
    const {NodeIO}=await import('@gltf-transform/core');
    const glb=await new NodeIO().readBinary(bytes);
    assert.ok(glb.getRoot().listMeshes()[0].listPrimitives()[0].getAttribute('COLOR_0'),'Preserve vertex colors in inline GLB');
    await fs.mkdir('target',{recursive:true});await page.screenshot({path:'target/journal-desktop.png'});
    console.log('PASS: inline colored GLBs, boolean geometry, true 2D cross-sections, download');

    await page.locator('[data-document="journal.assembly"]').click();await runPage();
    assert.match(await page.locator('.result-label').innerText(),/Model · volume 96.000/);
    await page.locator('[data-document="journal.pivot"]').click();await runPage();
    await page.waitForFunction(()=>document.querySelector('.solid-preview')?.dataset.animations==='1');
    const poses=()=>page.evaluate(()=>window.testViewers.filter(v=>v.el.isConnected).at(-1).api.inspect().positions);
    const pose=await poses();await delay(250);assert.notDeepEqual(await poses(),pose,'Pivot actually moves');
    console.log('PASS: require another document; pivot animation visibly changes transforms');

    await page.locator('#new-document').click();
    await page.locator('#document-name').fill('tests.my-journal');await page.locator('#document-title').fill('A regression journal');
    await page.getByRole('button',{name:'Create journal',exact:true}).click();await saved();
    const input=page.getByRole('textbox',{name:'Clojure code',exact:true}).first();
    const edit=async source=>{await input.click();await input.press('ControlOrMeta+a');await input.press('Backspace');if(source)await page.keyboard.insertText(source);};
    const select=async(from,to=from)=>page.evaluate(({from,to})=>{const {api}=window.testEditors.find(e=>e.el.isConnected);api.select(from,to);api.focus();},{from,to});
    const code=()=>page.evaluate(()=>window.testEditors.find(e=>e.el.isConnected).api.getValue());
    const label=()=>page.locator('.result-label').first().innerText();
    await edit('');await input.press('Backspace');await page.keyboard.type('(');assert.equal(await code(),'()');
    await page.keyboard.type(')');assert.equal(await code(),'()');
    for(const [source,cursor,key,expected] of [
      ['(a) b',2,'Control+Alt+ArrowRight','(a b)'],
      ['(a b)',2,'Control+Alt+ArrowLeft','(a) b'],
      ['a (b)',4,'Control+Alt+Shift+ArrowLeft','(a b)'],
      ['(a b)',3,'Control+Alt+Shift+ArrowRight','a (b)'],
      ['(a) "b )"',2,'Control+Alt+ArrowRight','(a "b )")'],
      ['(a) ; comment\nb',2,'Control+Alt+ArrowRight','(a ; comment\n  b)'],
      ['(a)\n(b\n c)',2,'Control+Alt+ArrowRight','(a\n  (b\n    c))'],
      ['(a\n  (b\n    c))',2,'Control+Alt+ArrowLeft','(a)\n(b\n  c)'],
      ['(a ; comment\n  b)',2,'Control+Alt+ArrowLeft','(a) ; comment\nb'],
      ['(a(b))',2,'Control+Alt+ArrowLeft','(a) (b)'],
      ['(a)b',2,'Control+Alt+ArrowRight','(a b)'],
      ['(a)\n"hello\n    world"',2,'Control+Alt+ArrowRight','(a\n  "hello\n    world")'],
      ['(a)\n#"hello\n    world"',2,'Control+Alt+ArrowRight','(a\n  #"hello\n    world")'],
      ['a(b)',3,'Control+Alt+Shift+ArrowLeft','(a b)'],
      ['(a(b))',2,'Control+Alt+Shift+ArrowRight','a ((b))'],
      ['#{a b}',3,'Control+Alt+Shift+ArrowRight','a #{b}'],
      ['#(a b)',3,'Control+Alt+Shift+ArrowRight','a #(b)'],
      ["'(a b)",3,'Control+Alt+Shift+ArrowRight',"a '(b)"]]) {
      await edit(source);await select(cursor);await input.press(key);assert.equal(await code(),expected,key);
      await input.press('ControlOrMeta+z');assert.equal(await code(),source,'Structural edit and formatting undo together');
    }
    await edit('(+ 1 (* 2 3))');await select(12);await input.press('ControlOrMeta+Enter');await ready();assert.equal(await label(),'6');
    await input.press('ControlOrMeta+Shift+E');await ready();assert.equal(await label(),'7');
    await select(3,4);await input.press('ControlOrMeta+Enter');await ready();assert.equal(await label(),'1');
    const countCode='(def calls (atom 0))\n(swap! calls inc)\n(throw (ex-info "Do not evaluate the suffix" {}))';
    await edit(countCode);await select(countCode.indexOf('\n(throw'));await input.press('ControlOrMeta+Enter');await ready();assert.equal(await label(),'1');
    await input.press('ControlOrMeta+Enter');await ready();assert.equal(await label(),'2','Same session, without repeating prefix definitions');
    await edit('(def shape (m/cube 2 3 4))');await input.press('Shift+Enter');await ready();assert.match(await label(),/volume 24.000/);
    console.log('PASS: autoparens, four structural commands, strings/comments, scalar/inner/top/selected forms, persistent namespace sessions, def previews');

    await page.locator('#vim-toggle').click();await input.click();await input.press('Escape');await page.keyboard.type('gg0i');await page.keyboard.insertText('; vim works\n');await input.press('Escape');
    assert.ok((await code()).startsWith('; vim works\n'));await page.locator('#vim-toggle').click();
    await edit('(a\n  (b\n    c))');
    await page.locator('#vim-toggle').click();await input.click();await input.press('Escape');
    await page.keyboard.type('gg0v');await page.keyboard.press('Shift+>');
    assert.equal(await code(),'(a)\n(b\n  c)','Visual > barfs and formats');
    await page.keyboard.type('gg0v');await page.keyboard.press('Shift+<');
    assert.equal(await code(),'(a\n  (b\n    c))','Visual < slurps and formats');
    await page.keyboard.type('i');await page.keyboard.type('<>');await input.press('Escape');
    assert.ok((await code()).includes('<>'),'Insert-mode angle brackets remain ordinary text');
    await page.locator('#vim-toggle').click();
    await page.getByRole('button',{name:'Split document vertically · Ctrl+Alt+S',exact:true}).click();
    assert.equal(await page.locator('.journal-pane').count(),2);
    await edit('(m/cube 3 3 3)');
    await page.waitForFunction(()=>{const e=window.testEditors.filter(e=>e.el.isConnected);return e.length===2&&e.every(e=>e.api.getValue()==='(m/cube 3 3 3)');});
    const prose=page.getByRole('textbox',{name:'Journal prose',exact:true}).first();await prose.click();await prose.press('ControlOrMeta+End');await prose.press('Enter');await page.keyboard.type('A shared note.');
    await page.waitForFunction(()=>Array.from(document.querySelectorAll('.journal-prose')).every(e=>e.textContent.includes('A shared note.')));
    await saved();await page.reload();await ready();assert.equal(await page.locator('.journal-pane').count(),2);assert.equal(await code(),'(m/cube 3 3 3)');
    const file=await fs.readFile(path.join(data,'documents/tests/my_journal.clj'),'utf8');assert.match(file,/\(ns tests.my-journal/);assert.match(file,/;; A shared note\./);assert.match(file,/\(m\/cube 3 3 3\)/);
    console.log('PASS: Vim, DataScript synchronization across splits, prose editing, saved layout and namespace .clj projection');

    const splitSource='(m/cube 3 3 3)\n\n(m/sphere 2)';
    await edit(splitSource);await select(16);await input.press('ControlOrMeta+Shift+Enter');
    await page.waitForFunction(()=>document.querySelectorAll('.code-block').length===4);
    const halves=await page.evaluate(()=>Array.from(document.querySelectorAll('.journal-pane')).map(p=>window.testEditors.filter(e=>p.contains(e.el)).map(e=>e.api.getValue())));
    assert.deepEqual(halves,[[splitSource.slice(0,16),splitSource.slice(16)],[splitSource.slice(0,16),splitSource.slice(16)]]);
    assert.equal(await page.evaluate(()=>document.activeElement.closest('.code-block')?.dataset.blockId),await page.locator('.journal-pane').first().locator('.code-block').nth(1).getAttribute('data-block-id'),'Focus moves to the new half');
    await saved();await page.reload();await ready();assert.equal(await page.locator('.code-block').count(),4);
    console.log('PASS: exact cursor split, synchronized panels, focus, persistence; formatted Vim visual slurp/barf');

    await page.keyboard.press('F1');assert.ok(await page.locator('#shortcuts').isVisible());await page.keyboard.press('Escape');assert.ok(!await page.locator('#shortcuts').isVisible());
    await edit('(loop [] (recur))');await input.press('Shift+Enter');await page.waitForFunction(()=>document.getElementById('engine').textContent==='Evaluating…');await page.locator('#stop').click();await ready();
    await edit('(m/cube 1 1 1)');await input.press('Shift+Enter');await ready();assert.match(await label(),/volume 1.000/);await saved();
    await page.locator('#new-document').click();await page.locator('#document-name').fill('tests.ai');
    await page.locator('#document-title').fill('Codex panels');await page.getByRole('button',{name:'Create journal',exact:true}).click();await saved();
    const aiPane=page.locator('.journal-pane').first();
    const aiProse=aiPane.getByRole('textbox',{name:'Journal prose',exact:true}).first();
    const writePrompt=async text=>{await aiProse.click();await aiProse.press('ControlOrMeta+a');await page.keyboard.insertText(text);};
    const thinking=()=>aiPane.locator('.thinking-block').first();
    await writePrompt('Make a colored cube.');await aiProse.press('ControlOrMeta+Enter');
    await thinking().waitFor();assert.equal(await aiPane.locator('.journal-block').nth(1).getAttribute('data-kind'),'thinking');
    await page.waitForFunction(()=>document.querySelector('.thinking-block')?.dataset.status==='complete');await saved();
    assert.deepEqual(await aiPane.locator('.journal-block').evaluateAll(es=>es.map(e=>e.dataset.kind)),['prose','thinking','prose','code','code']);
    assert.ok(!await aiPane.locator('.code-block').first().locator('.block-result').isVisible(),'Generated code is never auto-evaluated');
    await aiPane.locator('.code-block').first().getByRole('button',{name:'Run block · Shift+Enter',exact:true}).click();await ready();
    assert.match(await aiPane.locator('.result-label').first().innerText(),/volume 24.000/);
    await page.reload();await ready();assert.equal(await aiPane.locator('.journal-block').count(),5);
    assert.ok(!(await page.locator('body').innerText()).includes('PRIVATE_REASONING_FIXTURE_MUST_NOT_APPEAR'));
    let aiState=await (await fetch(url+'/api/state')).json();
    assert.ok(!JSON.stringify(aiState).includes('PRIVATE_REASONING_FIXTURE_MUST_NOT_APPEAR'),'Raw reasoning is not persisted');
    const aiFile=await fs.readFile(path.join(data,'documents/tests/ai.clj'),'utf8');assert.match(aiFile,/;; Codex response/);assert.match(aiFile,/m\/cube 2 3 4/);
    console.log('PASS: prose prompt → thinking → editable prose/code panels; no automatic execution; persistence without duplicate output or private reasoning');

    await writePrompt('WAIT_FIXTURE');
    const accepted=page.waitForResponse(r=>r.url().endsWith('/api/codex/requests')&&r.status()===202);
    await aiProse.press('ControlOrMeta+Enter');await accepted;await saved();await page.reload();await ready();
    await page.waitForFunction(()=>['queued','running'].includes(document.querySelector('.thinking-block')?.dataset.status));
    await thinking().getByRole('button',{name:'Stop this Codex request',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('.thinking-block')?.dataset.status==='cancelled');
    for(const prompt of ['FAIL_FIXTURE','INVALID_FIXTURE']){
      await writePrompt(prompt);await aiProse.press('ControlOrMeta+Enter');
      await page.waitForFunction(()=>document.querySelector('.thinking-block')?.dataset.status==='error');
      assert.ok(await thinking().getByRole('button',{name:'Send the current prose prompt again',exact:true}).isVisible());
    }
    await saved();
    const csrf=await fetch(url+'/api/codex/requests',{method:'POST',headers:{'Content-Type':'text/plain'},body:'{}'});assert.equal(csrf.status,415);
    // Node fetch may replace Host; use the raw HTTP client to test rebinding.
    const rebound=await new Promise((resolve,reject)=>require('node:http').get(url+'/api/state',
      {headers:{Host:'evil.example'}},r=>{r.resume();resolve(r.statusCode);}).on('error',reject));assert.equal(rebound,403);
    console.log('PASS: Codex reconnect/cancel, request and output failures, retry, CSRF and DNS-rebinding protection');
    const state=await (await fetch(url+'/api/state')).json();const document=state.documents.find(d=>d.namespace==='tests.my-journal');
    const stale=await fetch(url+'/api/documents/tests.my-journal',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({...document,revision:-10})});assert.equal(stale.status,409);
    const rejected=await fetch(url+'/api/workspace',{method:'PUT',headers:{'Content-Type':'application/json',Origin:'http://untrusted.invalid'},body:JSON.stringify(state.workspace)});assert.equal(rejected.status,403);
    await stop();await start();const restored=await (await fetch(url+'/api/state')).json();assert.deepEqual(restored,state);
    assert.deepEqual(errors,[]);console.log('PASS: dialog keys, cancellation/recovery, conflict/origin protection, Datahike restart persistence, zero browser errors');
    console.log('Journal tests passed. Isolated test store:',data);
  } catch(e) { console.error(e);console.error(logs.slice(-6000));process.exitCode=1; }
  finally {await browser?.close();await stop();}
})();
