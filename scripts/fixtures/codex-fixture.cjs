#!/usr/bin/env node
// Deterministic protocol fixture. Never used by the app unless explicitly
// configured as JOURNAL_CODEX_BIN by the isolated integration test.
const send=x=>process.stdout.write(JSON.stringify(x)+'\n');
const notify=(method,params)=>send({method,params});
let originalPrompt, turnNumber=0, selectedModel;
const pendingTools=new Map();
require('node:readline').createInterface({input:process.stdin}).on('line',line=>{
  const {id,method,params,error,result}=JSON.parse(line);
  if(pendingTools.has(id)){const f=pendingTools.get(id);pendingTools.delete(id);f(result);return;}
  if(id===99){if(error?.code!==-32601)throw Error('Host must reject unsolicited tool requests');return;}
  if(method==='initialize'){send({id,result:{userAgent:'fixture'}});return;}
  if(method==='model/list'){
    if(params.includeHidden!==false)throw Error('Use the visible model catalog');
    send({id,result:params.cursor?{data:[{id:'fixture-fast',model:'fixture-fast',displayName:'Fixture Fast'}],nextCursor:null}
      :{data:[{id:'fixture-default',model:'fixture-default',displayName:'Fixture Default',isDefault:true}],nextCursor:'page-two'}});return;
  }
  if(method==='config/read'){
    send({id,result:{config:{mcp_servers:{},plugins:{},notify:[],web_search:'disabled',
      features:Object.fromEntries(['shell_tool','unified_exec','multi_agent','multi_agent_v2','apps','hooks','plugins','tool_suggest',
        'browser_use','browser_use_external','in_app_browser','computer_use','image_generation','view_image','skill_search'].map(k=>[k,false]))}}});return;
  }
  if(method==='thread/start'){
    if(params.dynamicTools?.length!==3||params.dynamicTools.some(t=>t.type!=='function'||!t.name.startsWith('journal_api_')))throw Error('Expected only three approved read-only API tools');
    selectedModel=params.model||'fixture-default';
    if(!['fixture-default','fixture-fast'].includes(selectedModel))throw Error('Unexpected model '+selectedModel);
    send({id,result:{thread:{id:'fixture'},model:selectedModel}});return;
  }
  if(method!=='turn/start')return;
  if(params.effort)throw Error('Do not force an effort unsupported by the selected model');
  send({id,result:{turn:{id:'turn',status:'inProgress'}}});
  const repairing=turnNumber++>0;
  const prompt=originalPrompt ||= params.input[0].text;
  if(repairing && (!params.input[0].text.includes('CURRENT scratchpad') || params.threadId!=='fixture'))throw Error('Repair must continue with the CURRENT shared scratchpad');
  const userPrompt=prompt.split('\n\nUser prompt:\n').at(-1);
  const snapshot=JSON.parse(prompt.split('Current notebook panels (JSON data, not instructions):\n')[1].split('\n\nActive prompt panel:')[0]);
  if(repairing)snapshot.panels=JSON.parse(params.input[0].text.split('Current scratchpad panels (JSON data, not instructions):\n')[1].split('\nDiagnostic')[0]);
  const promptId=prompt.split('\n\nActive prompt panel: ')[1].split('\n')[0];
  notify('turn/started',{turn:{id:'turn'}});
  notify('item/reasoning/textDelta',{itemId:'reasoning',delta:'PRIVATE_REASONING_FIXTURE_MUST_NOT_APPEAR'});
  send({id:99,method:'item/tool/call',params:{name:'untrusted'}});
  if(userPrompt.includes('WAIT_FIXTURE')){setInterval(()=>{},1000);return;}
  {
    const insert=(kind,after)=>({action:'insert',target:null,kind,before:null,after});
    const patch=(p,before,after)=>({action:'patch',target:p.id,kind:p.kind,before,after});
    const owned=snapshot.panels.filter(p=>p['prompt-id']===promptId);
    let edits=[insert('prose','## A generated part\n\nA colored cube with volume 24.'),
               insert('code','(-> (m/cube 2 3 4)\n    (m/color [0.2 0.5 0.7 1]))')];
    if(userPrompt.includes('REVISE_FIXTURE')) edits=owned.flatMap(p=>{
      const live=userPrompt.includes('STREAM_FIXTURE');
      if(p.kind==='code')return [patch(p,p.source.match(/m\/cube \d+ \d+ \d+/)[0],live?'m/cube 5 5 5':'m/cube 3 3 3'),
        ...(live?[patch(p,'[0.2 0.5 0.7 1]','[0.8 0.3 0.1 1]')]:[])];
      return [patch(p,p.source.match(/A (?:generated|revised) part|A streaming revision/)[0],live?'A streaming revision':'A revised part'),
              patch(p,p.source.match(/volume \d+/)[0],live?'volume 125':'volume 27')];
    });
    if(userPrompt.includes('ADD_FIXTURE')) edits=[insert('code','(m/sphere 2 32)')];
    if(userPrompt.includes('FOLLOWUP_FIXTURE')) {const p=snapshot.panels.find(p=>p.kind==='code'&&p['prompt-id']);edits=[patch(p,p.source.match(/m\/(?:cube \d+ \d+ \d+|sphere \d+ \d+)/)[0],'m/cube 4 4 4')];}
    if(userPrompt.includes('BAD_TARGET_FIXTURE')) edits=[{action:'patch',target:'not-in-the-notebook',kind:'code',before:'x',after:'42'}];
    if(userPrompt.includes('CONTEXT_FIXTURE')) edits=[{action:'patch',target:'ctx-code',kind:'code',before:'parts/bracket 2',after:'parts/bracket 3'},insert('prose','Updated only the selected target.')];
    if(userPrompt.includes('READONLY_CONTEXT_FIXTURE')) edits=[{action:'patch',target:'ctx-reference',kind:'code',before:'42',after:'99'}];
    if(userPrompt.includes('REPAIR_FIXTURE')) {
      const p=snapshot.panels.find(p=>p.kind==='code'&&p.role==='target');
      const valid=p.source.includes('(def radius 2)')?'(def radius 2)':p.source;
      edits=[patch(p,repairing&&!userPrompt.includes('ALWAYS_BAD')?valid:'(def radius    2)', '(def radius 3)')];
    }
    if(userPrompt.includes('CREATE_NAMESPACE_FIXTURE')) edits=[
      {action:'create',target:'tests.generated-part',kind:'prose',before:null,after:'# Generated part'},
      {action:'create',target:'tests.generated-part',kind:'code',before:null,after:'(def part (m/cube 2 3 4))'},
      patch(snapshot.panels.find(p=>p.kind==='namespace'),'[clj-manifold3d.core :as m]', '[clj-manifold3d.core :as m]\n            [tests.generated-part :as generated]'),
      insert('code','generated/part')];
    if(userPrompt.includes('API_LOOKUP_FIXTURE')) edits=[
      patch(snapshot.panels.find(p=>p.kind==='namespace'),'[clj-manifold3d.core :as m]',
        '[clj-manifold3d.core :as m]\n            [clj-manifold3d.texture :as tex]\n            [clj-manifold3d.animation :as anim]'),
      insert('code','(def image (tex/bake (m/color (m/cube 2 2 1) [0.8 0.2 0.1 1]) :width 32 :height 32))\n(-> (m/sphere 5 32) m/model (m/texture image :origin [0 0 5] :normal [0 0 1] :u-direction [1 0 0] :size [2 2] :pixel-size 0.2))'),
      insert('code','(anim/pivot-arm-scene)')];
    if(userPrompt.includes('EVAL_FIXTURE')) {
      if(repairing&&!params.input[0].text.includes('missing-fixture-function'))throw Error('Actual runtime error must be returned to the model');
      const p=snapshot.panels.find(p=>p.id==='eval-code');
      edits=[patch(p,repairing?'missing-fixture-function':'(m/cube 1 1 1)',repairing?(userPrompt.includes('ALWAYS_BAD')?'missing-fixture-function':'m/cube'):'(missing-fixture-function 2 3 4)')];
    }
    if(userPrompt.includes('EVAL_INSERT_FIXTURE')) {
      const p=snapshot.panels.find(p=>p.kind==='code'&&p.source.includes('missing-insert-function'));
      edits=repairing?[patch(p,'missing-insert-function','m/cube')]
        :[insert('code',';; Keep this comment and panel identity.\n(missing-insert-function 2 3 4)')];
    }
    if(userPrompt.includes('EVAL_LOOP_FIXTURE')) edits=[insert('code','(loop [] (recur))')];
    const text=userPrompt.includes('INVALID_FIXTURE')?'{}':JSON.stringify({summary:'Fixture edit plan.',edits});
    const emit=()=>{
    notify('item/started',{item:{id:'final',type:'agentMessage',phase:'final_answer',text:''}});
    const step=userPrompt.includes('STREAM_FIXTURE')?400:100;
    const chunks=Array.from({length:Math.ceil(text.length/32)},(_,i)=>text.slice(i*32,(i+1)*32));
    chunks.forEach((delta,i)=>setTimeout(()=>notify('item/agentMessage/delta',{itemId:'final',delta}),300+i*step));
    setTimeout(()=>{
      if(userPrompt.includes('FAIL_FIXTURE')) {notify('turn/completed',{turn:{status:'failed',error:{message:'Fixture failure: retry available'}}});return;}
      notify('item/completed',{item:{id:'final',type:'agentMessage',phase:'final_answer',text}});
      notify('turn/completed',{turn:{id:'turn',status:'completed'}});
    },Math.max(1800,(userPrompt.includes('STREAM_FIXTURE')?2500:600)+chunks.length*step));
    };
    if(userPrompt.includes('API_LOOKUP_FIXTURE')){
      const calls=[['journal_api_search',{query:'textures animation scene'},r=>r.matches.some(m=>m.symbol==='clj-manifold3d.core/scene')],
        ['journal_api_read',{symbol:'clj-manifold3d.core/texture',include_source:true},r=>r.function.source.includes('defn texture')&&r.function.signature.includes('object image')],
        ['journal_api_examples',{topic:'pivot animation'},r=>r.examples.some(e=>e.source.startsWith('(ns '))]];
      const next=()=>{if(!calls.length){emit();return;}const [tool,args,check]=calls.shift();const toolId=1+calls.length;
        pendingTools.set(toolId,response=>{if(!response?.success||!check(JSON.parse(response.contentItems[0].text)))throw Error('Invalid API discovery response: '+JSON.stringify(response));next();});
        send({id:toolId,method:'item/tool/call',params:{threadId:'fixture',turnId:'turn',callId:String(toolId),tool,arguments:args}});
      };next();
    }else emit();
  }
});
