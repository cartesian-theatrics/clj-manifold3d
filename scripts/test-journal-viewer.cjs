// Real production worker, WebGL, Fullscreen and Document Picture-in-Picture.
// No user documents or Codex account are touched.
const assert=require('node:assert/strict'),fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path'),net=require('node:net');
const {spawn}=require('node:child_process'),{chromium}=require('playwright'),{decode}=require('fast-png');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function until(f,timeout=60000){const end=Date.now()+timeout;while(Date.now()<end){if(await f())return;await delay(100);}throw Error('Condition timed out');}
const mapping=process.env.JOURNAL_TEST_TEXTURE_MAPPING;
assert.ok(!mapping||['box','unwrap'].includes(mapping),'Expected box or unwrap');
const textureForm=mapping
 ? `(m/texture-all (m/cube 2 2 2 true) image :mapping :${mapping})`
 : '(m/texture (m/cube 2 2 2 true) image :mapping :planar)';
const source=`(let [track [{:time 0 :translation [0 0 0] :rotation [0 0 0 1] :scale [1 1 1]}
                    {:time 2 :translation [0 0 0] :rotation [0 0 1 0] :scale [1 1 1]}
                    {:time 4 :translation [0 0 0] :rotation [0 0 0 1] :scale [1 1 1]}]
             image (texture/bake (m/color (m/cube 2 2 0.1) [0 0 1 1]) :width 16 :height 16)
             colored (m/color (m/cube 2 2 2 true) [1 0.08 0.01 1])
             textured ${textureForm}]
         (m/scene {:nodes [{:id :colored :geometry colored :translation [-2 0 0]}
                           {:id :textured :geometry textured :translation [2 0 0]}]
                   :animations [{:channels [{:node :textured :path :rotation :track track}]}]}))`;
(async()=>{
 const data=await fs.mkdtemp(path.join(os.tmpdir(),'journal-viewer-'));
 const socket=net.createServer();await new Promise(r=>socket.listen(0,'127.0.0.1',r));const port=socket.address().port;await new Promise(r=>socket.close(r));
 const url=`http://127.0.0.1:${port}`;let logs='',browser;
 const server=spawn('clojure',['-M:journal-server'],{env:{...process.env,JOURNAL_DATA:data,JOURNAL_PORT:String(port),JOURNAL_CODEX_BIN:path.resolve('scripts/fixtures/codex-fixture.cjs')},stdio:['ignore','pipe','pipe']});
 server.stdout.on('data',x=>logs+=x);server.stderr.on('data',x=>logs+=x);
 const put=async(p,x)=>{const r=await fetch(url+p,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(x)});assert.equal(r.status,200,await r.text().then(t=>{try{return JSON.parse(t);}catch{return t;}}));};
 try{
  await until(async()=>{try{return (await fetch(url+'/api/state')).ok;}catch{return false;}},90000);
  await put('/api/documents/test.viewer',{namespace:'test.viewer',title:'Animated materials',revision:0,
   'ns-source':'(ns test.viewer (:require [clj-manifold3d.core :as m] [clj-manifold3d.texture :as texture]))',
   blocks:[{id:'note',kind:'prose',source:'Live colored and textured animation'},{id:'model',kind:'code',source},
           {id:'other',kind:'code',source:'(m/cube 1 1 1)'}]});
  await put('/api/workspace',{vim:false,'active-pane':'one',panes:[{id:'one',document:'test.viewer',width:1}]});
  browser=await chromium.launch({headless:!process.env.HEADED,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
  const page=await browser.newPage({viewport:{width:1440,height:1200}}),errors=[];
  page.on('pageerror',e=>{errors.push(e.message);console.error('Browser error:',e.message);});
  page.on('console',m=>{if(m.type()==='error')console.error('Console:',m.text());});
  await page.addInitScript(()=>Object.defineProperty(window,'journalBridge',{configurable:true,set(bridge){
   const create=bridge.createViewer;bridge.createViewer=(el,...args)=>{const api=create(el,...args);el.testViewer=api;return api;};
   Object.defineProperty(window,'journalBridge',{value:bridge});
  }}));
  await page.goto(url+'/journal/');await page.waitForFunction(()=>['Ready','Could not open journal'].includes(document.querySelector('#engine').textContent));
  assert.equal(await page.locator('#engine').innerText(),'Ready',await page.locator('#save-status').innerText());
  const panel=page.locator('[data-block-id="model"]');
  await panel.getByRole('button',{name:'Run block · Shift+Enter',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#engine').textContent==='Ready');
  await page.waitForFunction(()=>document.querySelector('[data-block-id="model"] .solid-preview')?.dataset.loaded==='true');
  const inspect=()=>page.evaluate(()=>document.querySelector('[data-block-id="model"] .solid-preview').testViewer.inspect());
  assert.equal((await inspect()).clips,1);
  const before=(await inspect()).positions;await delay(250);assert.notDeepEqual((await inspect()).positions,before);
  const download=page.waitForEvent('download');await panel.locator('[data-viewer-command=download]').click();
  const file=await (await download).path(),bytes=await fs.readFile(file),gltf=JSON.parse(bytes.subarray(20,20+bytes.readUInt32LE(12)).toString());
  assert.ok(gltf.meshes.some(m=>m.primitives.some(p=>p.attributes.COLOR_0!==undefined)));
  assert.ok(gltf.meshes.some(m=>m.primitives.some(p=>p.attributes.TEXCOORD_0!==undefined)));
  assert.ok(gltf.images[0].bufferView!==undefined);assert.equal(gltf.animations.length,1);
  const png=decode(await panel.locator('.solid-preview').screenshot());let red=0,blue=0;
  for(let i=0;i<png.data.length;i+=png.channels){const [r,g,b]=png.data.subarray(i,i+3);if(r>2*g&&r>2*b)red++;if(b>2*r&&b>2*g)blue++;}
  assert.ok(red>100&&blue>100,`Actual rendered color pixels: red=${red}, blue=${blue}`);
  console.log('PASS: production scene retains COLOR_0, texture UVs/PNG and animation; real viewer renders red and blue and animates');
  const camera=(await inspect()).camera;
  await panel.locator('[data-viewer-command=fullscreen]').click();
  await page.waitForFunction(()=>document.fullscreenElement?.classList.contains('block-result'));
  const box=await panel.locator('.solid-preview').boundingBox();assert.ok(box.width>1300&&box.height>800);
  await panel.locator('[data-viewer-command=fullscreen]').click();await page.waitForFunction(()=>!document.fullscreenElement);
  assert.deepEqual((await inspect()).camera,camera);
  console.log('PASS: in-place fullscreen, exit, full-size viewport and unchanged camera');
  await panel.locator('[data-viewer-command=popout]').click();
  await page.waitForFunction(()=>window.documentPictureInPicture?.window?.document.querySelector('.solid-preview')?.dataset.loaded==='true');
  const pipInspect=()=>page.evaluate(()=>window.documentPictureInPicture.window.document.querySelector('.solid-preview').testViewer.inspect());
  assert.deepEqual((await pipInspect()).camera,camera);
  assert.equal(await panel.locator('.solid-preview').count(),0,'The actual result moved, not a duplicate');
  assert.equal(await panel.locator('.viewer-placeholder').isVisible(),true);
  const pipBefore=(await pipInspect()).positions;await delay(250);assert.notDeepEqual((await pipInspect()).positions,pipBefore);
  // The same detached panel receives the new exported model after evaluation.
  await panel.locator('.cm-content').fill(source.replace('[1 0.08 0.01 1]','[0 1 0 1]'));
  await panel.getByRole('button',{name:'Run block · Shift+Enter',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#engine').textContent==='Ready');
  await page.waitForFunction(()=>window.documentPictureInPicture.window.document.querySelector('.solid-preview')?.dataset.loaded==='true');
  assert.equal((await pipInspect()).clips,1);assert.deepEqual((await pipInspect()).camera,camera);
  await page.evaluate(()=>window.documentPictureInPicture.window.close());
  await panel.locator('.solid-preview').waitFor();await page.waitForFunction(()=>document.querySelector('[data-block-id="model"] .solid-preview')?.dataset.loaded==='true');
  console.log('PASS: real always-on-top PiP, live re-evaluation, camera and animation retention, native-close returns viewer');
  // Browsers without Document PiP get ordinary, independently closable windows.
  await page.evaluate(()=>Object.defineProperty(window,'documentPictureInPicture',{value:undefined,configurable:true}));
  const popupEvent=page.waitForEvent('popup');await panel.locator('[data-viewer-command=popout]').click();const popup=await popupEvent;
  await popup.waitForFunction(()=>document.querySelector('.solid-preview')?.dataset.loaded==='true');
  assert.match(await popup.locator('.viewer-window-status').innerText(),/cannot pin windows/);
  await popup.locator('[data-viewer-command=pause]').click();
  await popup.waitForFunction(()=>document.querySelector('.solid-preview').testViewer.inspect().paused);
  await popup.locator('[data-viewer-command=popout]').click();await until(()=>popup.isClosed());
  await panel.locator('.solid-preview').waitFor();
  // Deleting the owning code panel must close its native window.
  const deletionPopup=page.waitForEvent('popup');await panel.locator('[data-viewer-command=popout]').click();const doomed=await deletionPopup;
  await doomed.waitForFunction(()=>document.querySelector('.solid-preview')?.dataset.loaded==='true');
  await panel.locator('.panel-delete').click();await until(()=>doomed.isClosed());
  assert.deepEqual(errors,[]);
  console.log('PASS: honest popup fallback, interactive controls, docking and panel-deletion cleanup; zero browser errors');
 }catch(e){console.error(e);console.error(logs.slice(-4000));process.exitCode=1;}
 finally{await browser?.close();server.kill('SIGTERM');await new Promise(r=>server.exitCode!==null?r():server.once('exit',r));}
})();
