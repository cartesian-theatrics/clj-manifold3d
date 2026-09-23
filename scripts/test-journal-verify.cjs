// Executes the production worker, including real WASM geometry export.
const assert=require('node:assert/strict');
const {verify}=require('./journal-verify.cjs');
const bundle=source=>({namespaces:['test.verify'],documents:[{
  namespace:'test.verify','ns-source':"(ns test.verify (:require [clj-manifold3d.core :as m]))",
  'verification/full?':true,blocks:[{id:'code',kind:'code',source}]
}]});
(async()=>{
  const success=await verify(bundle('(println "checked") (m/cube 2 3 4)'));
  assert.equal(success.status,'passed');
  const result=success.runs[0].results[0];
  assert.match(result.description,/volume 24/);assert.match(result.output,/checked/);
  assert.equal(Buffer.from(result['asset-base64'],'base64').subarray(0,4).toString(),'glTF');
  const section=await verify(bundle('(m/cross-section [[0 0] [2 0] [0 2]])'));
  assert.equal(section.status,'passed');assert.ok(section.runs[0].results[0].polygons.length);
  const failure=await verify(bundle('(missing-function 2 3 4)'));
  assert.equal(failure.status,'failed');assert.equal(failure.runs[0].block,'code');
  assert.match(failure.runs[0].message,/missing-function/);
  const timeout=await verify(bundle('(loop [] (recur))'),{timeout:1500});
  assert.equal(timeout.status,'failed');assert.match(timeout.runs[0].message,/timed out/);
  console.log('PASS: production worker GLB, cross-section, stdout, runtime error, and bounded nonterminating code');
})().catch(e=>{console.error(e);process.exitCode=1;});
