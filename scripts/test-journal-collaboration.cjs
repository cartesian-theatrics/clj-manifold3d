const assert=require('node:assert/strict'),fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path'),net=require('node:net');
const {spawn}=require('node:child_process'),{chromium}=require('playwright');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function until(f,timeout=60000){const end=Date.now()+timeout;while(Date.now()<end){if(await f())return;await delay(100);}throw Error('Condition timed out');}
(async()=>{
 const data=await fs.mkdtemp(path.join(os.tmpdir(),'journal-collaboration-'));
 const socket=net.createServer();await new Promise(r=>socket.listen(0,'127.0.0.1',r));const port=socket.address().port;await new Promise(r=>socket.close(r));
 const url=`http://127.0.0.1:${port}`;let logs='',browser;
 const server=spawn('clojure',['-M:journal-server'],{env:{...process.env,JOURNAL_DATA:data,JOURNAL_PORT:String(port),JOURNAL_CODEX_BIN:path.resolve('scripts/fixtures/codex-fixture.cjs')},stdio:['ignore','pipe','pipe']});
 server.stdout.on('data',x=>logs+=x);server.stderr.on('data',x=>logs+=x);
 const state=async()=>await(await fetch(url+'/api/state')).json();
 const put=async(p,x)=>{const r=await fetch(url+p,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(x)});if(!r.ok)throw Error(await r.text());return r.json();};
 try{
  await until(async()=>{try{return (await fetch(url+'/api/state')).ok;}catch{return false;}},90000);
  await put('/api/documents/test.collaboration',{namespace:'test.collaboration',title:'Shared scratchpad',revision:0,'ns-source':'(ns test.collaboration (:require [clj-manifold3d.core :as m]))',blocks:[
   {id:'prompt',kind:'prose',source:'EVAL_FIXTURE STREAM_FIXTURE Make the cube larger.'},
   {id:'eval-code',kind:'code',source:';; KEEP THIS COMMENT\n(m/cube 1 1 1)\n;; KEEP THIS TAIL'},
   {id:'note',kind:'prose',source:'An unrelated note.'}]});
  await put('/api/workspace',{vim:false,'active-pane':'one',panes:[{id:'one',document:'test.collaboration',width:1}]});
  browser=await chromium.launch({headless:true,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
  const a=await browser.newPage({viewport:{width:1480,height:1600}}),b=await browser.newPage({viewport:{width:1480,height:1600}}),errors=[];
  for(const p of [a,b]){
   p.on('pageerror',e=>errors.push(e.message));
   await p.addInitScript(()=>Object.defineProperty(window,'journalBridge',{configurable:true,set(bridge){
    window.editorTrace=[];const create=bridge.createCodeEditor;
    bridge.createCodeEditor=(el,opts)=>{const api=create(el,opts);for(const method of ['setPreview','setValue']){const f=api[method];api[method]=(...args)=>{const id=el.closest('[data-block-id]')?.dataset.blockId;if(id==='eval-code')window.editorTrace.push({method,args,before:api.getValue()});return f(...args);};}return api;};
    Object.defineProperty(window,'journalBridge',{value:bridge});
   }}));
   await p.goto(url+'/journal/');await p.waitForFunction(()=>document.querySelector('#engine').textContent==='Ready');
  }
  const code=p=>p.locator('[data-block-id="eval-code"] .cm-content');
  await a.locator('[data-block-id="prompt"]').getByRole('button',{name:'Send prose to Codex · Ctrl/Cmd+Enter (prose)',exact:true}).click();
  for(const p of [a,b])await p.waitForFunction(()=>document.querySelector('[data-block-id="eval-code"] .cm-content')?.getAttribute('contenteditable')==='false');
  await a.locator('[data-block-id="eval-code"]').scrollIntoViewIfNeeded();
  await a.locator('[data-block-id="eval-code"] .cm-line').first().evaluate(el=>window.originalLine=el);
  let s=await state(),doc=s.documents.find(d=>d.namespace==='test.collaboration');
  const staleDoc=structuredClone(doc);
  const locked=await fetch(url+'/api/documents/test.collaboration',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({...doc,blocks:doc.blocks.map(x=>x.id==='eval-code'?{...x,source:'42'}:x)})});
  assert.equal(locked.status,423,'Backend rejects a competing writer, not merely UI editing');
  await b.locator('[data-block-id="note"]').getByRole('textbox',{name:'Journal prose',exact:true}).fill('Another user can still edit this note.');
  await a.waitForFunction(()=>document.querySelector('[data-block-id="eval-code"] .cm-content')?.textContent.includes('missing-fixture-function'));
  await until(async()=>{s=await state();return s.requests.some(r=>r.verification&&JSON.parse(r.verification).some(v=>v.status==='failed'));});
  doc=s.documents.find(d=>d.namespace==='test.collaboration');assert.match(doc.blocks.find(x=>x.id==='eval-code').source,/missing-fixture-function/,'Failed candidate really lives in the shared document');
  const merged=await put('/api/documents/test.collaboration',{...staleDoc,title:'Saved from another tab','base-document':staleDoc});
  assert.equal(merged.title,'Saved from another tab');
  assert.notEqual(merged.blocks.find(x=>x.id==='eval-code').source,staleDoc.blocks.find(x=>x.id==='eval-code').source,'A stale save of unrelated fields must retain the newer shared code');
  const retained=await a.evaluate(()=>window.originalLine===document.querySelector('[data-block-id="eval-code"] .cm-line'));
  if(!retained)console.log('TRACE',JSON.stringify(await a.evaluate(()=>window.editorTrace)));
  assert.ok(retained,'Repair did not recreate the unchanged comment node');
  await until(async()=>{s=await state();return s.requests.some(r=>r['repair-input']);});
  const request=s.requests.find(r=>r.namespace==='test.collaboration');assert.match(request['repair-input'],/CURRENT scratchpad/);
  await a.waitForFunction(()=>document.querySelector('.thinking-block')?.dataset.status==='complete');
  await until(async()=>{s=await state();return s.documents.find(d=>d.namespace==='test.collaboration').blocks.find(x=>x.id==='note').source==='Another user can still edit this note.';});
  assert.match(await code(a).innerText(),/m\/cube 2 3 4/);assert.match(await code(b).innerText(),/m\/cube 2 3 4/);
  await a.waitForFunction(()=>document.querySelector('[data-block-id="eval-code"] .solid-preview')?.dataset.loaded==='true');
  assert.deepEqual(JSON.parse(s.requests.find(r=>r.id===request.id).verification).map(x=>x.status),['failed','passed']);
  const plan=JSON.parse(s.requests.find(r=>r.id===request.id)['working-plan']);
  assert.equal(plan.edits.length,2);assert.equal(plan.edits[1].before,'missing-fixture-function');assert.equal(plan.edits[1].after,'m/cube');
  console.log('PASS: actual shared code, locked across clients, server ownership, tiny incremental repair, unchanged DOM, unrelated user edit, automatic render');

  // New panels become real editors and keep their IDs when repaired.
  const prompt=a.locator('[data-block-id="prompt"]').getByRole('textbox',{name:'Journal prose',exact:true});
  await prompt.fill('EVAL_INSERT_FIXTURE STREAM_FIXTURE Add a cube.');await prompt.press('ControlOrMeta+Enter');
  await a.waitForFunction(()=>Array.from(document.querySelectorAll('.code-block .cm-content')).some(e=>e.textContent.includes('missing-insert-function')));
  const fresh=a.locator('.code-block').filter({hasText:'missing-insert-function'});const freshId=await fresh.getAttribute('data-block-id');
  await fresh.evaluate(el=>el.dataset.identity='kept');
  await a.waitForFunction(()=>document.querySelector('.thinking-block')?.dataset.status==='complete');
  assert.equal(await a.locator(`[data-block-id="${freshId}"][data-identity="kept"]`).count(),1);
  assert.match(await a.locator(`[data-block-id="${freshId}"] .cm-content`).innerText(),/m\/cube 2 3 4/);
  console.log('PASS: generated panel repaired in its actual code editor without replacement or duplicate insertion');

  // Cancellation retains applied edits and releases both clients' editors.
  await prompt.fill('REVISE_FIXTURE STREAM_FIXTURE Revise the generated cube.');await prompt.press('ControlOrMeta+Enter');
  await a.waitForFunction(()=>Array.from(document.querySelectorAll('.code-block .cm-content')).some(e=>e.textContent.includes('m/cube 5 5 5')));
  await a.locator('.thinking-block').first().getByRole('button',{name:'Stop this Codex request',exact:true}).click();
  await a.waitForFunction(()=>document.querySelector('.thinking-block')?.dataset.status==='cancelled');
  assert.match(await a.locator(`[data-block-id="${freshId}"] .cm-content`).innerText(),/m\/cube 5 5 5/);
  assert.equal(await a.locator(`[data-block-id="${freshId}"] .cm-content`).getAttribute('contenteditable'),'true');
  await a.reload();await a.waitForFunction(()=>document.querySelector('#engine').textContent==='Ready');
  assert.match(await a.locator(`[data-block-id="${freshId}"] .cm-content`).innerText(),/m\/cube 5 5 5/);
  assert.deepEqual(errors,[]);console.log('PASS: Stop keeps completed edits, releases locks, and survives reload; zero browser errors');
 }catch(e){console.error(e);console.error(logs.slice(-5000));process.exitCode=1;}
 finally{await browser?.close();server.kill('SIGTERM');await new Promise(r=>server.exitCode!==null?r():server.once('exit',r));}
})();
