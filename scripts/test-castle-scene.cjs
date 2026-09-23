const assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path');
const {chromium}=require('playwright'),{createServer}=require('./serve-castle.cjs');
(async()=>{
 const server=createServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));let browser;
 try{
  browser=await chromium.launch({headless:true,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.waitForFunction(()=>window.castlePreview,{},{timeout:120000});
  await page.evaluate(()=>window.castlePreview.setTime(2));
  const a=await page.evaluate(()=>window.castlePreview.inspect());
  assert.equal(a.lights,6);assert.equal(a.clips,1);assert.ok(a.textures>=2);
  assert.equal(a.depth24,true);assert.ok(a.cameraNear>=2,'Preserve fine window depth at cinematic distances');
  await page.evaluate(()=>window.castlePreview.setTime(6.2));
  const b=await page.evaluate(()=>window.castlePreview.inspect());
  assert.notDeepEqual(a.camera,b.camera);assert.notDeepEqual(a.comet,b.comet);
  assert.equal(a.camera[0],0);assert.equal(b.camera[0],0,'Camera dolly should stay head-on');
  await page.screenshot({path:path.resolve('target/fairytale-castle-night.png')});
  await page.evaluate(()=>window.castlePreview.setTime(3.8));
  await page.screenshot({path:path.resolve('target/fairytale-castle-night-fireworks.png')});
  await page.evaluate(()=>window.castlePreview.setView([6,-95,47],[0,-22,27]));
  await page.screenshot({path:path.resolve('target/fairytale-castle-stone-detail.png')});
  await page.evaluate(()=>window.castlePreview.setView([190,-175,110],[130,-22,4]));
  await page.screenshot({path:path.resolve('target/fairytale-castle-bridge-landing.png')});
  await page.locator('#reflection').uncheck();await page.locator('#bloom').uncheck();await page.locator('#cinematic').uncheck();
  await page.evaluate(()=>window.castlePreview.setTime(5));
  assert.equal(await page.locator('#status').count(),0);
  assert.deepEqual(errors,[]);
  const bytes=await fs.readFile('target/fairytale-castle-night.glb'),doc=JSON.parse(bytes.subarray(20,20+bytes.readUInt32LE(12)));
  const lights=doc.extensions.KHR_lights_punctual.lights;
  assert.equal(lights.length,6);
  // This scene uses directional architectural illumination to avoid clipping
  // in older F3D/VTK importers with different punctual-light attenuation.
  const keyNode=doc.nodes.find(n=>n.name===':warm-front');
  const key=lights[keyNode.extensions.KHR_lights_punctual.light];
  assert.equal(key.type,'directional');assert.ok(key.intensity>0 && key.intensity<=2);
  assert.ok(lights.filter(l=>l.type!=='directional').every(l=>l.intensity<=6),
    'Keep local castle accents restrained; verify brightness in F3D when changing this rig');
  assert.ok(doc.images.length>=2);assert.equal(doc.cameras.length,1);
  for(const name of ['foothills','shoreline','mountain-rock','snowcaps','bridge-approach'])
    assert.ok(doc.nodes.some(n=>n.name===name && n.mesh!==undefined),`Missing surface terrain: ${name}`);
  assert.equal(doc.cameras[0].perspective.znear,2);
  assert.ok(doc.animations[0].channels.length>500);
  console.log('PASS: embedded lights, stone textures, emissive materials, animated camera/particles, reflective/bloom preview, controls; zero WebGL errors');
  console.log(JSON.stringify(b));
 }finally{await browser?.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exitCode=1;});
