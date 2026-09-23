// Host-owned runner, not a model tool or shell. Uses the production journal
// worker in a disposable browser, with no journal API or external network.
const {chromium}=require('playwright');
const fs=require('node:fs/promises');
const path=require('node:path');

async function verify(bundle, {timeout=30000}={}) {
  const root=path.resolve(__dirname,'..'), origin='http://journal-verification.invalid';
  const browser=await chromium.launch({headless:true, executablePath:process.env.CHROMIUM_PATH});
  try {
    const context=await browser.newContext({serviceWorkers:'block'});
    await context.route('**/*',async route=>{
      const url=new URL(route.request().url()), p=decodeURIComponent(url.pathname);
      if(url.origin!==origin||route.request().method()!=='GET')return route.abort();
      if(p==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><title>Journal verification</title>'});
      if(!/^\/(?:journal\/worker|wasm)\/[A-Za-z0-9_./-]+\.(?:js|wasm)$/.test(p)||p.includes('..'))return route.abort();
      try {const body=await fs.readFile(path.join(root,'public',p));return route.fulfill({body,contentType:p.endsWith('.wasm')?'application/wasm':'text/javascript'});}
      catch{return route.abort();}
    });
    const page=await context.newPage();await page.goto(origin);
    return await page.evaluate(async ({bundle,timeout})=>{
      const runs=[];let totalBytes=0;
      for(const namespace of bundle.namespaces){
        const document=bundle.documents.find(d=>d.namespace===namespace);
        const run=await new Promise(resolve=>{
          const worker=new Worker('/journal/worker/worker.js'),results=[];
          let finished=false;
          const finish=report=>{if(finished)return;finished=true;clearTimeout(timer);worker.terminate();resolve({namespace,'ns-source':document['ns-source'],'full?':!!document['verification/full?'],sources:document.blocks.filter(b=>b.kind==='code'&&!b.id.startsWith('verification-defs-')).map(b=>({id:b.id,source:b.source})),results,...report});};
          const timer=setTimeout(()=>finish({status:'failed',message:'Evaluation timed out after '+timeout+'ms.'}),timeout);
          worker.onerror=e=>finish({status:'failed',message:String(e.message).slice(0,4000)});
          worker.onmessage=({data:d})=>{
            if(d.type==='ready')worker.postMessage({id:'verify',namespace,documents:bundle.documents,blocks:document.blocks,all:true});
            if(d.type==='result'){
              if(results.length>=500)return finish({status:'failed',message:'Too many evaluation results.'});
              if(d.block.startsWith('verification-defs-'))return;
              totalBytes+=(d.buffer?.byteLength||0)+(d.polygons?JSON.stringify(d.polygons).length:0);
              if(totalBytes>16*1024*1024)return finish({status:'failed',message:'Rendered results exceed the 16 MiB verification budget; reduce mesh resolution.'});
              let asset;
              if(d.buffer){const bytes=new Uint8Array(d.buffer),parts=[];for(let i=0;i<bytes.length;i+=8192)parts.push(String.fromCharCode(...bytes.subarray(i,i+8192)));asset=btoa(parts.join(''));}
              results.push({block:d.block,kind:d.kind,description:String(d.description||'').slice(0,2000),output:String(d.output||'').slice(0,4000),bytes:d.buffer?.byteLength||0,...(asset?{'asset-base64':asset}:{}),...(d.polygons?{polygons:d.polygons}:{})});
            }
            if(d.type==='done')finish({status:'passed'});
            if(d.type==='error'||d.type==='fatal')finish({status:'failed',block:d.block,message:String(d.message).slice(0,4000),output:String(d.output||'').slice(0,4000)});
          };
        });
        runs.push(run);if(run.status!=='passed')return {status:'failed',runs};
      }
      return {status:'passed',runs};
    },{bundle,timeout});
  } finally {await browser.close();}
}
module.exports={verify};
if(require.main===module){
  let input='';process.stdin.setEncoding('utf8');
  process.stdin.on('data',x=>{input+=x;if(input.length>2000000){process.stderr.write('Verification input too large');process.exit(1);}});
  process.stdin.on('end',async()=>{try{const report=await verify(JSON.parse(input));process.stdout.write(JSON.stringify(report));}
    catch(e){process.stdout.write(JSON.stringify({status:'unavailable',message:String(e.message).slice(0,4000)}));process.exitCode=1;}});
}
