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
    // Interaction tests use the compact solids fixture, independently of the
    // application's castle split-view welcome layout.
    const layout=await fetch(url+'/api/workspace',{method:'PUT',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({vim:false,'active-pane':'pane-first',panes:[{id:'pane-first',document:'journal.first-shapes',width:1}]})});
    assert.equal(layout.status,200);
    browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
    const page=await browser.newPage({viewport:{width:1480,height:1100},acceptDownloads:true});
    const errors=[];page.on('pageerror',e=>{errors.push(e.message);console.error('Browser error:',e.message);});
    await page.addInitScript(()=>{
      // Instrument resource bridges, not application state; the production app
      // still reads/writes facts exclusively through DataScript transactions.
      Object.defineProperty(window,'journalBridge',{configurable:true,set(bridge){
        window.testEditors=[];window.testNamespaceEditors=[];window.testViewers=[];
        const editor=bridge.createCodeEditor,viewer=bridge.createViewer;
        bridge.createCodeEditor=(el,opts)=>{const api=editor(el,opts);(opts.label==='Namespace declaration'?window.testNamespaceEditors:window.testEditors).push({el,api});return api;};
        bridge.createViewer=(el,...args)=>{const api=viewer(el,...args);window.testViewers.push({el,api});return api;};
        Object.defineProperty(window,'journalBridge',{value:bridge});
      }});
    });
    await page.goto(url+'/journal/');
    const ready=()=>page.waitForFunction(()=>document.getElementById('engine').textContent==='Ready',null,{timeout:45000});
    const saved=()=>page.waitForFunction(()=>document.getElementById('save-status').textContent==='Saved');
    await ready();
    await page.waitForFunction(()=>document.querySelector('#codex-model option[value="fixture-fast"]'));
    assert.deepEqual(await page.locator('#codex-model option').evaluateAll(es=>es.map(e=>e.value)),['','fixture-default','fixture-fast'],'Paginated model discovery');
    await page.locator('#codex-model').selectOption('fixture-fast');await saved();await page.reload();await ready();
    assert.equal(await page.locator('#codex-model').inputValue(),'fixture-fast','Model selection survives reload');
    const runPage=async()=>{await page.getByRole('button',{name:'Evaluate document · Ctrl/Cmd+Alt+Enter',exact:true}).first().click();await ready();};
    await runPage();
    assert.match((await page.locator('.result-label').allTextContents()).join(' '),/volume 96.000.*volume 89.860.*Cross-section/);
    await page.locator('.section-preview svg').scrollIntoViewIfNeeded();
    await page.locator('.solid-preview').first().scrollIntoViewIfNeeded();
    await page.waitForFunction(()=>document.querySelector('.solid-preview')?.dataset.loaded==='true');
    const firstViewer=()=>page.evaluate(()=>window.testViewers.filter(v=>v.el===document.querySelector('.solid-preview')).at(-1).api.inspect());
    const modelPanel=page.locator('.code-block').first();
    assert.equal((await firstViewer()).grid,true,'Floor grid starts on');
    assert.ok(!await modelPanel.locator('.viewer-advanced').isVisible(),'Tools start hidden');
    await modelPanel.locator('.viewer-tools-toggle').click();
    await modelPanel.locator('.viewer-measure').click();
    const canvas=modelPanel.locator('canvas');
    await canvas.scrollIntoViewIfNeeded();
    const rect=await canvas.boundingBox();
    await page.mouse.click(rect.x+rect.width/2-20,rect.y+rect.height/2);
    await page.mouse.click(rect.x+rect.width/2+20,rect.y+rect.height/2);
    const measured=await firstViewer();
    assert.equal(measured.points.length,2,'Ray picking returns two actual surface points');
    const distance=Math.hypot(...measured.points[0].map((x,i)=>x-measured.points[1][i]));
    assert.ok(distance>0);
    assert.match(await modelPanel.locator('.measurement-label').innerText(),new RegExp(distance.toFixed(4)));
    for(const p of measured.points)assert.ok(Math.abs(Math.abs(p[2])-.5)<1e-5||Math.abs(Math.abs(p[0])-6)<1e-5||Math.abs(Math.abs(p[1])-4)<1e-5,'Points are on the plate, not on the floor grid');
    await modelPanel.locator('.viewer-grid').click();assert.equal((await firstViewer()).grid,false);
    await modelPanel.locator('.viewer-measure').click();
    const cameraBefore=(await firstViewer()).camera;
    await page.mouse.move(rect.x+rect.width/2,rect.y+rect.height/2);
    await page.mouse.down();await page.mouse.move(rect.x+rect.width/2+90,rect.y+rect.height/2+35,{steps:12});await page.mouse.up();
    let lastCamera=(await firstViewer()).camera;
    await until(async()=>{
      await delay(250);const current=(await firstViewer()).camera;
      const stable=Math.hypot(...current.position.map((x,i)=>x-lastCamera.position[i]))<1e-6;
      lastCamera=current;return stable;
    });
    await delay(1000);await saved();
    const cameraAfter=(await firstViewer()).camera;
    assert.notDeepEqual(cameraAfter.position,cameraBefore.position);
    await modelPanel.getByRole('button',{name:'Run block · Shift+Enter',exact:true}).click();await ready();
    await page.waitForFunction(()=>document.querySelector('.solid-preview')?.dataset.loaded==='true');
    const closeCamera=actual=>actual.position.forEach((x,i)=>assert.ok(Math.abs(x-cameraAfter.position[i])<1e-4,'Camera survives reload: '+JSON.stringify({actual,expected:cameraAfter})));
    closeCamera((await firstViewer()).camera);
    await saved();await page.reload();await ready();await runPage();
    await page.waitForFunction(()=>document.querySelector('.solid-preview')?.dataset.loaded==='true');
    closeCamera((await firstViewer()).camera);assert.equal((await firstViewer()).grid,false);
    assert.ok(!await modelPanel.locator('.viewer-advanced').isVisible(),'Tools hidden again after page reload');
    console.log('PASS: actual two-point surface measurement, optional grid, camera persistence across re-evaluation and page reload');
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

    await page.locator('[data-document="journal.flag-uv"]').click();await runPage();
    assert.match(await page.locator('.result-label').last().innerText(),/Model · volume/);
    const flagPreview=page.locator('.solid-preview').last();
    await flagPreview.scrollIntoViewIfNeeded();
    await page.waitForFunction(el=>el.dataset.loaded==='true',await flagPreview.elementHandle());
    const flagDownload=page.waitForEvent('download');
    await flagPreview.locator('..').getByRole('button',{name:'Download this result as GLB · Ctrl+Alt+D',exact:true}).click();
    const flagBytes=await fs.readFile(await (await flagDownload).path());
    const flagGLB=await new NodeIO().readBinary(flagBytes);
    assert.ok(flagGLB.getRoot().listTextures().some(t=>t.getImage()?.length>1000),'GLB embeds the flag image');
    assert.ok(flagGLB.getRoot().listMeshes().some(m=>m.listPrimitives().some(p=>p.getAttribute('TEXCOORD_0'))),'GLB retains mapped UVs');
    await fs.writeFile('target/journal-flag.glb',flagBytes);
    await page.screenshot({path:'target/journal-flag.png'});
    console.log('PASS: American flag sphere example runs and exports embedded texture plus UVs');

    await page.locator('#new-document').click();
    await page.locator('#document-name').fill('tests.my-journal');await page.locator('#document-title').fill('A regression journal');
    await page.getByRole('button',{name:'Create journal',exact:true}).click();await saved();
    const input=page.getByRole('textbox',{name:'Clojure code',exact:true}).first();
    const edit=async source=>{await input.click();await input.press('ControlOrMeta+a');await input.press('Backspace');if(source)await page.keyboard.insertText(source);};
    const select=async(from,to=from)=>page.evaluate(({from,to})=>{const {api}=window.testEditors.find(e=>e.el.isConnected);api.select(from,to);api.focus();},{from,to});
    const code=()=>page.evaluate(()=>window.testEditors.find(e=>e.el.isConnected).api.getValue());
    const label=()=>page.locator('.result-label').first().innerText();
    const nsInput=page.getByRole('textbox',{name:'Namespace declaration',exact:true}).first();
    const editNs=async source=>{await nsInput.click();await nsInput.press('ControlOrMeta+a');await page.keyboard.insertText(source);};
    const defaultNs='(ns tests.my-journal\n  (:require [clj-manifold3d.core :as m]))';
    assert.equal(await nsInput.innerText(),defaultNs,'New documents visibly declare their only default alias');
    await edit('(math/cos 0)');await input.press('Shift+Enter');await ready();assert.match(await label(),/Could not resolve.*math\/cos/);
    await editNs('(ns tests.my-journal\n  (:require [clj-manifold3d.core :as m]\n            [clj-manifold3d.math :as math]))');
    await input.press('Shift+Enter');await ready();assert.equal(await label(),'1','Adding an import invalidates the cached SCI context');
    await editNs('(ns tests.my-journal\n  (:require [clj-manifold3d.core :as shape]))');
    await edit('(m/cube 2 3 4)');await input.press('Shift+Enter');await ready();assert.match(await label(),/Could not resolve.*m\/cube/,'Removing an alias actually removes it');
    await edit('(shape/cube 2 3 4)');await input.press('Shift+Enter');await ready();assert.match(await label(),/volume 24.000/);
    await editNs('(ns tests.my-journal (:require [missing.library :as missing]))');
    await input.press('Shift+Enter');await ready();assert.match(await page.locator('.namespace-error').innerText(),/missing.library/);
    await editNs(defaultNs);await edit('(m/cube 2 3 4)');await input.press('Shift+Enter');await ready();assert.match(await label(),/volume 24.000/);
    await saved();
    assert.ok((await fs.readFile(path.join(data,'documents/tests/my_journal.clj'),'utf8')).startsWith(defaultNs+'\n\n'));
    console.log('PASS: visible namespace header, explicit imports, alias add/remove invalidation, import errors and recovery, exact .clj projection');
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
    await writePrompt('STREAM_FIXTURE Make a colored cube.');await aiProse.press('ControlOrMeta+Enter');
    await thinking().waitFor();assert.equal(await aiPane.locator('.journal-block').nth(1).getAttribute('data-kind'),'thinking');
    await page.locator('#codex-model').selectOption('');
    await page.waitForFunction(()=>document.querySelector('.live-draft .draft-source')?.textContent.length>0);
    const firstDraftLength=await aiPane.locator('.draft-source').first().evaluate(e=>e.textContent.length);
    assert.equal(await thinking().getAttribute('data-status'),'running','Draft arrives before completion');
    assert.equal(await aiPane.locator('.code-block').count(),1,'Partial additions are not executable document blocks');
    await page.waitForFunction(n=>document.querySelector('.draft-source')?.textContent.length>n,firstDraftLength);
    let during=await (await fetch(url+'/api/state')).json();
    assert.equal(during.documents.find(d=>d.namespace==='tests.ai').blocks.length,3,'Drafts persist as request state, not namespace source');
    assert.ok(during.requests.some(r=>r.status==='running'&&JSON.parse(r.preview||'[]').length));
    await page.locator('.journal-pane').nth(1).locator('.document-select').selectOption('tests.ai');
    await page.waitForFunction(()=>Array.from(document.querySelectorAll('.journal-pane')).every(p=>p.querySelector('.live-draft')));
    await saved();await page.reload();await ready();
    assert.ok(await thinking().locator('.live-draft').count()>0,'Reload restores the in-flight draft');
    await page.waitForFunction(()=>document.querySelector('.thinking-block')?.dataset.status==='complete');await saved();
    assert.equal(await aiPane.locator('.live-draft').count(),0,'Validated completion replaces drafts');
    assert.deepEqual(await aiPane.locator('.journal-block').evaluateAll(es=>es.map(e=>e.dataset.kind)),['prose','thinking','prose','code','code']);
    assert.ok(await aiPane.locator('.code-block').first().locator('.block-result').isVisible(),'Verified execution renders the generated panel automatically');
    await aiPane.locator('.code-block').first().locator('.solid-preview').scrollIntoViewIfNeeded();
    await page.waitForFunction(()=>document.querySelector('.code-block .solid-preview')?.dataset.loaded==='true');
    assert.match(await aiPane.locator('.result-label').first().innerText(),/volume 24.000/);
    await aiPane.locator('.code-block').first().getByRole('button',{name:'Run block · Shift+Enter',exact:true}).click();await ready();
    assert.match(await aiPane.locator('.result-label').first().innerText(),/volume 24.000/);
    await page.reload();await ready();assert.equal(await aiPane.locator('.journal-block').count(),5);
    assert.ok(!(await page.locator('body').innerText()).includes('PRIVATE_REASONING_FIXTURE_MUST_NOT_APPEAR'));
    let aiState=await (await fetch(url+'/api/state')).json();
    const modelRequest=aiState.requests.find(r=>r.namespace==='tests.ai');
    assert.equal(modelRequest.model,'fixture-fast');assert.equal(modelRequest['resolved-model'],'fixture-fast');
    assert.equal(await thinking().locator('.thinking-model').innerText(),'fixture-fast','Changing selection does not change an existing request');
    const changedModel=await fetch(url+'/api/codex/requests',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...modelRequest,model:'fixture-default',context:'',snapshot:modelRequest.snapshot})});
    assert.equal(changedModel.status,409,'An existing request ID cannot switch models');
    console.log('PASS: dynamic paginated model selector, saved preference, immutable request model, resolved model in Thinking');
    assert.ok(!JSON.stringify(aiState).includes('PRIVATE_REASONING_FIXTURE_MUST_NOT_APPEAR'),'Raw reasoning is not persisted');
    const aiFile=await fs.readFile(path.join(data,'documents/tests/ai.clj'),'utf8');assert.match(aiFile,/;; Codex response/);assert.match(aiFile,/m\/cube 2 3 4/);
    console.log('PASS: prose prompt → verification → automatically rendered code panels; persistence without duplicate output or private reasoning');

    const generatedId=await aiPane.locator('.code-block').first().getAttribute('data-block-id');
    const generated=aiPane.locator(`[data-block-id="${generatedId}"]`);
    const generatedSource=()=>generated.locator('.cm-content').innerText();
    const originalGenerated=await generatedSource();
    const revisedSource=originalGenerated.replace('m/cube 2 3 4','m/cube 3 3 3');
    const waitApplied=()=>page.waitForFunction(()=>document.querySelector('.thinking-block')?.textContent.includes('Fixture edit plan.'));
    await generated.getByRole('button',{name:'Run block · Shift+Enter',exact:true}).click();await ready();
    await generated.locator('.panel-toggle').click();
    await writePrompt('REVISE_FIXTURE Make this cube 3 by 3 by 3.');await aiProse.press('ControlOrMeta+Enter');
    await waitApplied();await saved();
    assert.equal(await aiPane.locator('.code-block').count(),2,'Revise existing code, no duplicate output');
    assert.ok(!await generated.locator('.block-editor').isVisible(),'Keep hidden state on replacement');
    assert.equal(await generated.locator('.block-result').getAttribute('data-status'),'ready');
    assert.match(await generated.locator('.result-label').innerText(),/volume 27.000/);
    assert.match(await thinking().innerText(),/Updated 2, added 0/);
    await generated.locator('.panel-toggle').click();assert.equal(await generatedSource(),revisedSource,'Only the requested fragment changed; color and formatting survive');
    const generatedInput=generated.getByRole('textbox',{name:'Clojure code',exact:true});
    await generatedInput.click();await generatedInput.press('ControlOrMeta+z');
    assert.match(await generatedSource(),/m\/cube 2 3 4/,'One undo reverses the completed rewrite, not one streamed chunk');
    await generatedInput.press('ControlOrMeta+Shift+z');assert.equal(await generatedSource(),revisedSource);
    await generated.getByRole('button',{name:'Run block · Shift+Enter',exact:true}).click();await ready();
    assert.match(await generated.locator('.result-label').innerText(),/volume 27.000/);
    await saved();await page.reload();await ready();assert.equal(await generatedSource(),revisedSource);
    assert.equal(await aiPane.locator('.code-block').count(),2);
    assert.match(await fs.readFile(path.join(data,'documents/tests/ai.clj'),'utf8'),/m\/cube 3 3 3/);

    const generatedProse=aiPane.locator('.prose-block').nth(1);
    const originalProse=await generatedProse.locator('.journal-prose').innerText();
    await writePrompt('STREAM_FIXTURE REVISE_FIXTURE Change this example.');await aiProse.press('ControlOrMeta+Enter');
    await generated.locator('.streaming-tools').waitFor();
    await page.waitForFunction(()=>document.querySelector('.panel-streaming.code-block .cm-content')?.textContent.includes('m/cube 5 5 5'));
    assert.equal(await generated.locator('.live-draft').count(),0,'No secondary rewrite panel');
    assert.equal(await generatedSource(),revisedSource.replace('m/cube 3 3 3','m/cube 5 5 5'),'First delta applies while every unrelated character stays intact');
    assert.equal(await generated.locator('.cm-content').getAttribute('contenteditable'),'false');
    assert.ok(await generated.locator('button[title="Run block · Shift+Enter"]').isDisabled());
    assert.notEqual(await generatedProse.locator('.journal-prose').innerText(),originalProse,'Rich prose also streams in place');
    assert.equal(await generatedProse.locator('.journal-prose').getAttribute('contenteditable'),'false');
    const originalOnServer=(await(await fetch(url+'/api/state')).json()).documents.find(d=>d.namespace==='tests.ai').blocks.find(b=>b.id===generatedId);
    assert.match(originalOnServer.source,/m\/cube 5 5 5/,'Completed edits already live in the shared document');
    await page.waitForFunction(()=>document.querySelector('.panel-streaming.code-block .cm-content')?.textContent.includes('[0.8 0.3 0.1 1]'));
    assert.equal(await generatedSource(),revisedSource.replace('m/cube 3 3 3','m/cube 5 5 5').replace('[0.2 0.5 0.7 1]','[0.8 0.3 0.1 1]'),'A second disjoint patch applies without replacing the panel');
    await page.reload();await ready();
    assert.ok(await generated.locator('.streaming-tools').isVisible(),'In-place streaming is restored after reload');
    await generated.getByRole('button',{name:'Stop generation and keep completed edits for you to continue',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('.thinking-block')?.dataset.status==='cancelled');
    assert.equal(await aiPane.locator('.live-draft').count(),0);
    assert.match(await generatedSource(),/m\/cube 5 5 5/,'Cancellation preserves completed edits');
    assert.notEqual(await generatedProse.locator('.journal-prose').innerText(),originalProse);
    assert.equal(await generated.locator('.cm-content').getAttribute('contenteditable'),'true');
    await generatedInput.click();await generatedInput.press('ControlOrMeta+z');
    assert.equal(await generatedSource(),revisedSource,'A stopped session is one undoable edit');await saved();
    console.log('PASS: growing partial additions, durable shared deltas, split/reload synchronization, Stop keeps edits, explicit undo');

    await writePrompt('ADD_FIXTURE Also show a sphere as a separate example.');await aiProse.press('ControlOrMeta+Enter');
    await waitApplied();await saved();assert.equal(await aiPane.locator('.code-block').count(),3);
    assert.equal(await generatedSource(),revisedSource,'Addition leaves prior code intact');
    // A new prose block may ask to revise earlier work, not necessarily append.
    await generated.getByRole('button',{name:'Insert prose after this block · Ctrl+Alt+T',exact:true}).click();
    const followup=generated.locator('xpath=following-sibling::section[1]');
    const followupId=await followup.getAttribute('data-block-id');
    await followup.getByRole('textbox',{name:'Journal prose',exact:true}).press('ControlOrMeta+a');
    await page.keyboard.insertText('FOLLOWUP_FIXTURE Make the earlier generated code a cube of side four.');
    await followup.getByRole('textbox',{name:'Journal prose',exact:true}).press('ControlOrMeta+Enter');
    await page.waitForFunction(()=>Array.from(document.querySelectorAll('.thinking-progress')).some(p=>p.textContent.includes('Updated 1, added 0')));
    await saved();assert.equal(await aiPane.locator('.code-block').count(),3);
    // Fixture deliberately chooses an existing generated panel by ID.
    assert.ok((await aiPane.locator('.code-block .cm-content').allTextContents()).some(s=>s.includes('(m/cube 4 4 4)')));

    await writePrompt('REVISE_FIXTURE Correct the current example.');
    const revisionAccepted=page.waitForResponse(r=>r.url().endsWith('/api/codex/requests')&&r.status()===202);
    await aiProse.press('ControlOrMeta+Enter');await revisionAccepted;
    const editable=generated.getByRole('textbox',{name:'Clojure code',exact:true});
    assert.equal(await generated.locator('.cm-content').getAttribute('contenteditable'),'false');
    await generated.getByRole('button',{name:'Stop generation and keep completed edits for you to continue',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('.thinking-block')?.dataset.status==='cancelled');
    await editable.click();await editable.press('ControlOrMeta+a');await page.keyboard.insertText('(m/cube 9 9 9)');
    assert.equal(await generatedSource(),'(m/cube 9 9 9)','Stop hands ownership back to the user');await saved();
    await page.reload();await ready();assert.equal(await generatedSource(),'(m/cube 9 9 9)');
    assert.equal(await thinking().getAttribute('data-status'),'cancelled');
    await thinking().getByRole('button',{name:'Send the current prose prompt again',exact:true}).click();
    await waitApplied();await saved();assert.equal(await generatedSource(),'(m/cube 3 3 3)');
    await writePrompt('BAD_TARGET_FIXTURE');await aiProse.press('ControlOrMeta+Enter');
    await page.waitForFunction(()=>document.querySelector('.thinking-block')?.dataset.status==='error');
    assert.equal(await generatedSource(),'(m/cube 3 3 3)','Unknown target cannot mutate the notebook');
    console.log('PASS: stable in-place revisions, explicit additions, follow-up edits, stale previews, concurrent-edit conflicts/retry, invalid targets');

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
    // Panel controls apply to generated thinking, prose, and code alike.
    await thinking().locator('.panel-toggle').click();
    assert.ok(!await thinking().locator('.thinking-progress').isVisible());
    await thinking().locator('.panel-toggle').click();
    assert.ok(await thinking().locator('.thinking-progress').isVisible());
    const deletedThinking=await thinking().getAttribute('data-block-id');
    await thinking().locator('.panel-delete').click();await saved();
    assert.equal(await page.locator(`[data-block-id="${deletedThinking}"]`).count(),0);
    await writePrompt('WAIT_FIXTURE');
    const activeAccepted=page.waitForResponse(r=>r.url().endsWith('/api/codex/requests')&&r.status()===202);
    await aiProse.press('ControlOrMeta+Enter');await activeAccepted;
    const activeThinking=await thinking().getAttribute('data-block-id');
    await thinking().locator('.panel-delete').click();await saved();
    await until(async()=>['cancelled','interrupted'].includes((await (await fetch(url+`/api/codex/requests/${activeThinking}`)).json()).status));
    await page.reload();await ready();
    assert.equal(await page.locator(`[data-block-id="${activeThinking}"]`).count(),0);
    // Delete during submission, before the backend knows the request ID.
    await page.route('**/api/codex/requests',async route=>{await delay(800);await route.continue();});
    const submitting=page.waitForRequest(r=>r.url().endsWith('/api/codex/requests')&&r.method()==='POST');
    await aiProse.press('ControlOrMeta+Enter');await submitting;
    const submittedThinking=await thinking().getAttribute('data-block-id');
    await thinking().locator('.panel-delete').click();
    await until(async()=>{const r=await fetch(url+`/api/codex/requests/${submittedThinking}`);return r.ok&&(await r.json()).status==='cancelled';});
    await page.unroute('**/api/codex/requests');await saved();
    assert.equal(await page.locator(`[data-block-id="${submittedThinking}"]`).count(),0);

    await page.locator('#new-document').click();await page.locator('#document-name').fill('tests.panels');
    await page.locator('#document-title').fill('Panel controls');await page.getByRole('button',{name:'Create journal',exact:true}).click();await saved();
    const panelPane=page.locator('.journal-pane').first();
    const otherPane=page.locator('.journal-pane').nth(1);
    await otherPane.locator('.document-select').selectOption('tests.panels');await saved();
    const codePanel=panelPane.locator('.code-block');const prosePanel=panelPane.locator('.prose-block');
    await codePanel.getByRole('button',{name:'Run block · Shift+Enter',exact:true}).click();await ready();
    assert.ok(await codePanel.locator('.block-result').isVisible());
    const beforeHide=await fs.readFile(path.join(data,'documents/tests/panels.clj'),'utf8');
    await codePanel.locator('.panel-toggle').click();
    assert.ok(!await codePanel.locator('.block-result').isVisible());
    assert.ok(!await codePanel.locator('.block-editor').isVisible());
    assert.ok(await codePanel.getByRole('button',{name:'Show panel · Ctrl+Alt+H',exact:true}).isVisible());
    assert.ok(!await otherPane.locator('.code-block .block-editor').isVisible());
    await saved();assert.equal(await fs.readFile(path.join(data,'documents/tests/panels.clj'),'utf8'),beforeHide);
    await page.reload();await ready();assert.ok(!await codePanel.locator('.block-editor').isVisible());
    await codePanel.locator('.panel-toggle').click();
    assert.ok(await codePanel.locator('.block-editor').isVisible());
    await codePanel.locator('.panel-toggle').press('Control+Alt+h');
    assert.ok(!await codePanel.locator('.block-editor').isVisible());
    await codePanel.locator('.panel-toggle').press('Control+Alt+h');
    assert.ok(await codePanel.locator('.block-editor').isVisible());
    await prosePanel.locator('.panel-toggle').click();
    assert.ok(!await prosePanel.locator('.block-editor').isVisible());
    await prosePanel.locator('.panel-toggle').click();
    assert.ok(await prosePanel.locator('.block-editor').isVisible());
    await codePanel.locator('.panel-delete').click();
    assert.equal(await panelPane.locator('.code-block').count(),0);
    assert.equal(await otherPane.locator('.code-block').count(),0);
    const proseId=await prosePanel.getAttribute('data-block-id');
    await prosePanel.locator('.panel-toggle').focus();
    await prosePanel.locator('.panel-toggle').press('Control+Alt+Backspace');
    assert.notEqual(await prosePanel.getAttribute('data-block-id'),proseId);
    assert.equal(await panelPane.locator('.journal-block').count(),1);
    await saved();await page.reload();await ready();
    assert.equal(await panelPane.locator('.journal-block').count(),1);
    assert.equal((await prosePanel.getByRole('textbox',{name:'Journal prose',exact:true}).innerText()).trim(),'');
    assert.ok(!(await fs.readFile(path.join(data,'documents/tests/panels.clj'),'utf8')).includes('(m/cube'));
    console.log('PASS: hide/show every panel, hide results, sync/reload, delete/keyboard shortcuts, final-panel editing, cancellation on thinking deletion');
    await panelPane.locator('.undo-delete').click();
    assert.equal(await prosePanel.getAttribute('data-block-id'),proseId,'Undo restores the final panel and removes only the untouched placeholder');
    await prosePanel.locator('.panel-toggle').press('Control+Alt+z');
    assert.equal(await panelPane.locator('.code-block').count(),1);
    assert.equal(await otherPane.locator('.code-block').count(),1,'Undo synchronizes splits');
    await saved();await page.reload();await ready();
    assert.equal(await panelPane.locator('.code-block').count(),1);
    console.log('PASS: undo delete survives reload, restores panel identities/order, synchronizes splits');
    // Context selection is persisted application state, not a DOM-only filter.
    const seed=async(namespace,blocks,imports='')=>{
      const r=await fetch(url+'/api/documents/'+namespace,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({namespace,'ns-source':`(ns ${namespace}\n  (:require [clj-manifold3d.core :as m]${imports}))`,title:namespace,revision:0,blocks})});
      assert.equal(r.status,200,await r.text());
    };
    await seed('tests.context-lib',[{id:'ctx-lib',kind:'code',source:'(defn bracket "A small bracket." [size]\n  ;; IMPLEMENTATION_ONLY_SENTINEL\n  (m/cube size 3 4))\n(def unused "DO_NOT_SEND_LIBRARY_SENTINEL")'}]);
    await seed('tests.context',[
      {id:'ctx-prompt',kind:'prose',source:'CONTEXT_FIXTURE Make the bracket bigger.'},
      {id:'ctx-code',kind:'code',source:"(parts/bracket 2)\n;; keep tail",hidden:true},
      {id:'ctx-reference',kind:'code',source:'(def reference 42)'},
      {id:'ctx-exclude',kind:'prose',source:'DO_NOT_SEND_CONTEXT_SENTINEL'}], '\n            [tests.context-lib :as parts]');
    await page.reload();await ready();
    const ctxPane=page.locator('.journal-pane').first();
    await ctxPane.locator('.document-select').selectOption('tests.context');await saved();
    const ctxPrompt=ctxPane.locator('[data-block-id="ctx-prompt"]');
    const openContext=()=>ctxPrompt.getByRole('button',{name:'Choose targets, references and pinned instructions · Ctrl+Alt+K (prose)',exact:true}).click();
    await openContext();assert.ok(await page.locator('#context-dialog').isVisible());
    await page.locator('#context-scope').selectOption('selected');
    await page.getByLabel('Context role for panel ctx-code',{exact:true}).selectOption('target');
    await page.getByLabel('Context role for panel ctx-reference',{exact:true}).selectOption('reference');
    await page.getByLabel('Context role for panel ctx-exclude',{exact:true}).selectOption('exclude');
    await page.locator('#context-workspace-instructions').fill('Default to millimeters.');
    await page.locator('#context-document-instructions').fill('Use centimeters in this document.');
    assert.match(await page.locator('#context-definitions').innerText(),/tests.context-lib\/bracket.*read-only.*signature/);
    assert.match(await page.locator('#context-definitions').innerText(),/A small bracket/);
    const inspect=async()=>{await page.locator('#context-inspect').click();await page.waitForFunction(()=>!document.getElementById('context-inspection').hidden);return page.locator('#context-input').innerText();};
    let inspected=await inspect();
    assert.match(inspected,/Default to millimeters/);assert.match(inspected,/Use centimeters/);
    assert.ok(!inspected.includes('DO_NOT_SEND_CONTEXT_SENTINEL'));
    assert.ok(!inspected.includes('DO_NOT_SEND_LIBRARY_SENTINEL'));
    assert.ok(!inspected.includes('IMPLEMENTATION_ONLY_SENTINEL'));
    assert.match(await page.locator('#context-token-estimate').innerText(),/Rough estimate/);
    await page.getByRole('button',{name:'Include implementation',exact:true}).click();
    inspected=await inspect();assert.match(inspected,/IMPLEMENTATION_ONLY_SENTINEL/);
    const inspectedContext=JSON.parse(inspected.split('Current notebook panels (JSON data, not instructions):\n')[1].split('\n\nActive prompt panel:')[0]);
    assert.ok(inspectedContext.dependencies.some(d=>d.symbol==='clj-manifold3d.core/cube'),'Referenced library signatures are included too: '+JSON.stringify(inspectedContext.dependencies.map(d=>d.symbol)));
    await page.screenshot({path:'target/journal-context.png'});
    await page.locator('#context-instructions-mode').selectOption('replace');
    inspected=await inspect();assert.ok(!inspected.includes('Default to millimeters'));
    await page.locator('#context-dialog [data-dialog-close]').click();await saved();
    assert.equal(await ctxPane.locator('[data-block-id="ctx-code"]').getAttribute('data-context-role'),'target');
    assert.equal(await ctxPane.locator('[data-block-id="ctx-reference"]').getAttribute('data-context-role'),'reference');
    assert.ok(!await ctxPane.locator('[data-block-id="ctx-code"] .block-editor').isVisible(),'Context never unhides a panel');
    await page.reload();await ready();await openContext();
    assert.equal(await page.locator('#context-scope').inputValue(),'selected');
    assert.equal(await page.locator('#context-instructions-mode').inputValue(),'replace');
    assert.equal(await page.locator('#context-workspace-instructions').inputValue(),'Default to millimeters.');
    assert.equal(await page.getByLabel('Context role for panel ctx-reference',{exact:true}).inputValue(),'reference');
    inspected=await inspect();
    const sent=page.waitForResponse(r=>r.url().endsWith('/api/codex/requests')&&r.status()===202);
    await page.locator('#context-send').click();const sentRequest=await(await sent).json();
    await page.waitForFunction(()=>document.querySelector('[data-block-id="ctx-prompt"] + .thinking-block')?.dataset.status==='complete');await saved();
    const actualInspection=await(await fetch(url+'/api/codex/requests/'+sentRequest.id+'/inspection')).json();
    assert.equal(actualInspection.text,inspected,'The inspected text is exactly the model input saved by the server');
    let ctxState=await(await fetch(url+'/api/state')).json();
    const ctxDoc=ctxState.documents.find(d=>d.namespace==='tests.context');
    assert.match(ctxDoc.blocks.find(b=>b.id==='ctx-code').source,/parts\/bracket 3/);
    assert.equal(ctxDoc.blocks.find(b=>b.id==='ctx-reference').source,'(def reference 42)');
    const ctxInput=ctxPrompt.getByRole('textbox',{name:'Journal prose',exact:true});
    await ctxInput.click();await ctxInput.press('ControlOrMeta+a');await page.keyboard.insertText('READONLY_CONTEXT_FIXTURE Try to change the reference.');
    await ctxInput.press('ControlOrMeta+Enter');
    await page.waitForFunction(()=>document.querySelector('[data-block-id="ctx-prompt"] + .thinking-block')?.dataset.status==='error');
    assert.equal(await ctxPane.locator('[data-block-id="ctx-reference"] .cm-content').innerText(),'(def reference 42)','Read-only targets are rejected by the host');
    await saved();
    console.log('PASS: per-prompt selection, reference enforcement, exclusion privacy, exact inspection, persisted pins, namespace signatures/expansion and built-in library catalog');
    await seed('tests.repairs',[
      {id:'repair-prompt',kind:'prose',source:'REPAIR_FIXTURE Change radius to 3.'},
      {id:'repair-code',kind:'code',source:';; Keep this exact comment\n(def radius 2)\n(m/sphere radius 32)'}]);
    await page.reload();await ready();await ctxPane.locator('.document-select').selectOption('tests.repairs');
    const repairPrompt=ctxPane.locator('[data-block-id="repair-prompt"]');
    await repairPrompt.getByRole('button',{name:'Send prose to Codex · Ctrl/Cmd+Enter (prose)',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('[data-block-id="repair-prompt"] + .thinking-block')?.dataset.status==='complete');await saved();
    assert.equal(await ctxPane.locator('[data-block-id="repair-code"] .cm-content').innerText(),';; Keep this exact comment\n(def radius 3)\n(m/sphere radius 32)');
    let repairState=await(await fetch(url+'/api/state')).json();
    const repaired=repairState.requests.find(r=>r.namespace==='tests.repairs');
    assert.equal(repaired['repair-attempt'],1);assert.match(repaired['rejected-output'],/radius    2/);
    const repairInspection=await(await fetch(url+'/api/codex/requests/'+repaired.id+'/inspection')).json();
    assert.match(repairInspection['repair-input'],/CURRENT scratchpad/);
    const repairInput=repairPrompt.getByRole('textbox',{name:'Journal prose',exact:true});
    await repairInput.fill('REPAIR_FIXTURE ALWAYS_BAD Keep rejecting.');
    await repairInput.press('ControlOrMeta+Enter');
    await page.waitForFunction(()=>document.querySelector('[data-block-id="repair-prompt"] + .thinking-block')?.dataset.status==='error');await saved();
    repairState=await(await fetch(url+'/api/state')).json();
    const exhausted=repairState.requests.find(r=>r.prompt.includes('ALWAYS_BAD'));
    assert.equal(exhausted['repair-attempt'],2);assert.match(exhausted.error,/Edit 1, panel repair-code/);
    assert.equal(await ctxPane.locator('[data-block-id="repair-code"] .cm-content').innerText(),';; Keep this exact comment\n(def radius 3)\n(m/sphere radius 32)');
    console.log('PASS: exact-match patch repair in same thread, minimal delta, retained diagnostics, bounded retry exhaustion without mutation');

    await repairInput.fill('CREATE_NAMESPACE_FIXTURE Factor a new namespace.');
    await repairInput.press('ControlOrMeta+Enter');
    await page.waitForFunction(()=>document.querySelector('[data-block-id="repair-prompt"] + .thinking-block')?.dataset.status==='error');
    assert.equal(await page.locator('[data-document="tests.generated-part"]').count(),0,'Creation disabled by default');
    await repairPrompt.getByRole('button',{name:'Choose targets, references and pinned instructions · Ctrl+Alt+K (prose)',exact:true}).click();
    await page.locator('#context-create-namespaces').check();
    inspected=await inspect();assert.match(inspected,/"allow-create-namespaces\?":true/);
    await page.locator('#context-send').click();
    await page.waitForFunction(()=>document.querySelector('[data-block-id="repair-prompt"] + .thinking-block')?.dataset.status==='complete');await saved();
    assert.equal(await page.locator('[data-document="tests.generated-part"]').count(),1);
    assert.match(await fs.readFile(path.join(data,'documents/tests/generated_part.clj'),'utf8'),/\(def part \(m\/cube 2 3 4\)\)/);
    const generatedUse=ctxPane.locator('.code-block').filter({hasText:'generated/part'});
    await generatedUse.getByRole('button',{name:'Run block · Shift+Enter',exact:true}).click();await ready();
    assert.match(await generatedUse.locator('.result-label').innerText(),/volume 24.000/);
    await page.reload();await ready();
    assert.equal(await page.locator('[data-document="tests.generated-part"]').count(),1,'Created namespace remains unique after reload');
    console.log('PASS: explicit namespace-creation permission, streaming creation, .clj projection and require from originating document');
    await seed('tests.discovery',[
      {id:'discover-prompt',kind:'prose',source:'API_LOOKUP_FIXTURE Use texture and animation APIs not mentioned in my code.'},
      {id:'discover-code',kind:'code',source:'(m/cube 1 1 1)'}]);
    await page.reload();await ready();await ctxPane.locator('.document-select').selectOption('tests.discovery');await saved();
    await ctxPane.locator('[data-block-id="discover-prompt"]').getByRole('button',{name:'Send prose to Codex · Ctrl/Cmd+Enter (prose)',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('.namespace-header.panel-streaming')?.textContent.includes('clj-manifold3d.texture'),null,{timeout:30000});
    assert.equal(await ctxPane.locator('.namespace-header .cm-content').getAttribute('contenteditable'),'false','Namespace deltas preview in the actual header');
    await page.waitForFunction(()=>document.querySelector('[data-block-id="discover-prompt"] + .thinking-block')?.dataset.status==='complete');await saved();
    const discovery=(await(await fetch(url+'/api/state')).json()).requests.find(r=>r.namespace==='tests.discovery');
    const lookupLog=JSON.parse(discovery['tool-log']);
    assert.deepEqual(lookupLog.map(e=>e.tool),['journal_api_search','journal_api_read','journal_api_examples']);
    assert.ok(!('api-catalog' in discovery),'The frozen catalog is server-side, not copied into frontend state');
    assert.ok(lookupLog[1].result.function.source.includes('defn texture'));
    assert.match(await ctxPane.locator('.namespace-header .cm-content').innerText(),/clj-manifold3d.texture :as tex/);
    assert.equal(JSON.parse(discovery.verification).at(-1).status,'passed');
    assert.ok(!('evaluation-input' in discovery));
    const discoveryResults=(await ctxPane.locator('.result-label').allTextContents()).join('\n');
    assert.match(discoveryResults,/Model · volume/);assert.match(discoveryResults,/Scene ·.*animations/);
    const projection=await fs.readFile(path.join(data,'documents/tests/discovery.clj'),'utf8');
    assert.equal((projection.match(/\(ns /g)||[]).length,1);assert.ok(!projection.includes('(require '));
    await saved();console.log('PASS: host API search/read/examples round trips, bounded read-only discovery, live namespace patch, generated texture + animation evaluation');
    await seed('tests.execution',[
      {id:'eval-prompt',kind:'prose',source:'EVAL_FIXTURE Make this cube larger.'},
      {id:'eval-code',kind:'code',source:';; keep this comment\n(m/cube 1 1 1)'}]);
    await page.reload();await ready();await ctxPane.locator('.document-select').selectOption('tests.execution');await saved();
    const evalPrompt=ctxPane.locator('[data-block-id="eval-prompt"]');
    await evalPrompt.getByRole('button',{name:'Send prose to Codex · Ctrl/Cmd+Enter (prose)',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('[data-block-id="eval-prompt"] + .thinking-block')?.dataset.status==='complete');await saved();
    const execRequest=(await(await fetch(url+'/api/state')).json()).requests.find(r=>r.namespace==='tests.execution');
    assert.deepEqual(JSON.parse(execRequest.verification).map(v=>v.status),['failed','passed']);
    assert.match(execRequest['repair-input'],/missing-fixture-function/);
    assert.equal(await ctxPane.locator('[data-block-id="eval-code"] .cm-content').innerText(),';; keep this comment\n(m/cube 2 3 4)');
    assert.match(await ctxPane.locator('.result-label').innerText(),/volume 24.000/);
    await page.waitForFunction(()=>document.querySelector('[data-block-id="eval-code"] .solid-preview')?.dataset.loaded==='true');
    const artifact=JSON.parse(execRequest['verified-results']).runs[0].results[0].asset;
    assert.equal((await fetch(url+artifact)).status,200);
    assert.equal((await fetch(url+artifact.replace(execRequest.id,'00000000-0000-0000-0000-000000000000'))).status,404);
    await page.reload();await ready();
    await page.waitForFunction(()=>document.querySelector('[data-block-id="eval-code"] .solid-preview')?.dataset.loaded==='true');
    assert.match(await ctxPane.locator('.result-label').innerText(),/volume 24.000/,'Persisted verified GLB hydrates without re-execution');
    // Restore the input for a deliberately unrepairable attempt.
    const evalInput=ctxPane.locator('[data-block-id="eval-code"] .cm-content');
    await evalInput.fill(';; keep this comment\n(m/cube 1 1 1)');await saved();
    const evalProse=evalPrompt.getByRole('textbox',{name:'Journal prose',exact:true});
    await evalProse.fill('EVAL_FIXTURE ALWAYS_BAD Test bounded failures.');await evalProse.press('ControlOrMeta+Enter');
    await page.waitForFunction(()=>document.querySelector('[data-block-id="eval-prompt"] + .thinking-block')?.dataset.status==='error');await saved();
    const failed=(await(await fetch(url+'/api/state')).json()).requests.find(r=>r.prompt==='EVAL_FIXTURE ALWAYS_BAD Test bounded failures.');
    assert.deepEqual(JSON.parse(failed.verification).map(v=>v.status),['failed','failed','failed']);
    assert.equal(await evalInput.innerText(),';; keep this comment\n(missing-fixture-function 2 3 4)','Failed code remains available for user repair');
    await evalInput.fill(';; keep this comment\n(m/cube 1 1 1)');await saved();
    await evalProse.fill('EVAL_LOOP_FIXTURE Test cancellation during evaluation.');await evalProse.press('ControlOrMeta+Enter');
    await page.waitForFunction(()=>document.querySelector('[data-block-id="eval-prompt"] + .thinking-block .thinking-progress')?.textContent.includes('Evaluating code'));
    await ctxPane.locator('.thinking-block').first().getByRole('button',{name:'Stop this Codex request',exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('[data-block-id="eval-prompt"] + .thinking-block')?.dataset.status==='cancelled');await saved();
    assert.equal(await ctxPane.locator('.code-block').count(),2,'Stopped evaluation keeps its inserted code panel');
    console.log('PASS: actual runtime failure → repair → automatic inline render; GLB persistence and ownership; bounded failed repairs; cancellation during evaluation');
    const state=await (await fetch(url+'/api/state')).json();const document=state.documents.find(d=>d.namespace==='tests.my-journal');
    const collision=await fetch(url+'/api/documents-batch',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify([
      {...document,title:'Must not commit'},
      {...state.documents.find(d=>d.namespace==='tests.generated-part'),'create-only':true}
    ])});
    assert.equal(collision.status,409);
    assert.deepEqual((await(await fetch(url+'/api/state')).json()).documents,state.documents,'Namespace collision rolls back all documents in the batch');
    const stale=await fetch(url+'/api/documents/tests.my-journal',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({...document,revision:-10})});assert.equal(stale.status,409);
    const rejected=await fetch(url+'/api/workspace',{method:'PUT',headers:{'Content-Type':'application/json',Origin:'http://untrusted.invalid'},body:JSON.stringify(state.workspace)});assert.equal(rejected.status,403);
    await stop();await start();const restored=await (await fetch(url+'/api/state')).json();assert.deepEqual(restored,state);
    assert.deepEqual(errors,[]);console.log('PASS: dialog keys, cancellation/recovery, atomic namespace collision protection, conflict/origin protection, Datahike restart persistence, zero browser errors');
    console.log('Journal tests passed. Isolated test store:',data);
  } catch(e) { console.error(e);console.error(logs.slice(-6000));process.exitCode=1; }
  finally {await browser?.close();await stop();}
})();
