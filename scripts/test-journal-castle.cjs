// Production SCI/Closure + fresh WASM, real Datahike persistence, no prebuilt GLB.
const assert=require('node:assert/strict'),fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path');
const {spawn}=require('node:child_process'),net=require('node:net'),{chromium}=require('playwright');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const data=await fs.mkdtemp(path.join(os.tmpdir(),'journal-castle-'));
 const socket=net.createServer();await new Promise(r=>socket.listen(0,'127.0.0.1',r));
 const port=socket.address().port;await new Promise(r=>socket.close(r));
 const url=`http://127.0.0.1:${port}`;let server,browser,logs='';
 const start=async()=>{
  server=spawn('clojure',['-M:journal-server'],{env:{...process.env,JOURNAL_DATA:data,JOURNAL_PORT:String(port),JOURNAL_CODEX_BIN:path.resolve('scripts/fixtures/codex-fixture.cjs')},stdio:['ignore','pipe','pipe']});
  server.stdout.on('data',x=>logs+=x);server.stderr.on('data',x=>logs+=x);
  for(let i=0;i<900;i++){
   if(server.exitCode!==null)throw Error(logs);
   try{if((await fetch(url+'/api/state')).ok)return;}catch{}
   await delay(100);
  }throw Error(logs);
 };
 const stop=async()=>{if(server?.exitCode===null){const done=new Promise(r=>server.once('exit',r));server.kill('SIGTERM');await done;}};
 try{
  await start();
  const snapshot=await (await fetch(url+'/api/state')).json();
  const architecture=snapshot.documents.find(d=>d.namespace==='journal.castle-architecture');
  const night=snapshot.documents.find(d=>d.namespace==='journal.castle-night');
  assert.ok(architecture&&night,'Both linked documents are seeded');
  assert.match(night['ns-source'],/journal.castle-architecture :as castle/);
  const edit=architecture.blocks.find(b=>b.kind==='code');edit.source+='\n;; retained local edit';
  const saved=await fetch(url+'/api/documents/journal.castle-architecture',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(architecture)});
  assert.equal(saved.status,200,await saved.text());
  await stop();await start();
  const persisted=(await (await fetch(url+'/api/state')).json()).documents.find(d=>d.namespace===architecture.namespace);
  assert.ok(persisted.blocks.some(b=>b.source.endsWith(';; retained local edit')),'Startup does not overwrite edited examples');
  console.log('PASS: linked example namespaces seeded; local edits survive restart');
  browser=await chromium.launch({headless:true,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
  const page=await browser.newPage({viewport:{width:1440,height:1000},acceptDownloads:true}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  page.on('console',m=>{if(m.type()==='error')console.error(m.text());});
  await page.addInitScript(()=>Object.defineProperty(window,'journalBridge',{configurable:true,set(bridge){
   const create=bridge.createViewer;bridge.createViewer=(el,...args)=>{const api=create(el,...args);el.testViewer=api;return api;};
   Object.defineProperty(window,'journalBridge',{value:bridge});
  }}));
  await page.goto(url+'/journal/');
  await page.waitForFunction(()=>document.getElementById('engine').textContent==='Ready',null,{timeout:60000});
  await page.locator('[data-pane-id="pane-architecture"]').getByRole('button',{name:'Close pane · Ctrl+Alt+W',exact:true}).click();
  await page.locator('[data-document="journal.castle-night"]').click();
  const startTime=Date.now();
  await page.getByRole('button',{name:'Evaluate document · Ctrl/Cmd+Alt+Enter',exact:true}).click();
  console.log('Evaluating complete castle source in optimized SCI worker…');
  await page.waitForFunction(()=>document.getElementById('engine').textContent==='Ready',null,{timeout:600000});
  assert.equal(await page.locator('.block-result[data-status="error"]').count(),0,await page.locator('.block-result[data-status="error"]').allTextContents());
  const model=page.locator('.solid-preview').last();await model.scrollIntoViewIfNeeded();
  await page.waitForFunction(el=>el.dataset.loaded==='true',await model.elementHandle(),{timeout:120000});
  console.log(`Castle evaluated/rendered in ${((Date.now()-startTime)/1000).toFixed(1)}s`);
  const inspect=()=>model.evaluate(el=>el.testViewer.inspect());
  const a=await inspect();await delay(350);const b=await inspect();
  assert.equal(a.authoredLights,6);assert.equal(a.authoredCamera,true);assert.equal(a.clips,1);
  assert.ok(a.meshCount>600);assert.ok(Math.abs(a.camera.position[0])<1e-5);assert.ok(a.camera.near>=2);
  assert.notDeepEqual(a.positions,b.positions,'Particles actually animate');
  const download=page.waitForEvent('download');
  await model.locator('..').getByRole('button',{name:'Download this result as GLB · Ctrl+Alt+D',exact:true}).click();
  const bytes=await fs.readFile(await (await download).path());
  const glb=JSON.parse(bytes.subarray(20,20+bytes.readUInt32LE(12)));
  assert.equal(glb.extensions.KHR_lights_punctual.lights.length,6);
  assert.ok(glb.images.length>=2);assert.equal(glb.cameras.length,1);
  assert.ok(glb.animations[0].channels.length>800);
  for(const name of ['foothills','shoreline','mountain-rock','snowcaps','bridge-approach'])
   assert.ok(glb.nodes.some(n=>n.name===name && n.mesh!==undefined),name);
  await fs.writeFile('target/journal-castle.glb',bytes);
  await model.locator('..').locator('[data-viewer-command="fullscreen"]').click();
  await delay(700);await page.screenshot({path:'target/journal-castle.png'});
  assert.deepEqual(errors,[]);
  console.log('PASS: full editable castle, native BVH, generated stone PNG, surface terrain, authored lights/camera, animation and GLB download');
 }finally{await browser?.close();await stop();}
})().catch(e=>{console.error(e);process.exitCode=1;});
