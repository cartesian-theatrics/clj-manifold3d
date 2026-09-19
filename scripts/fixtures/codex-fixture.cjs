#!/usr/bin/env node
// Deterministic protocol fixture. Never used by the app unless explicitly
// configured as JOURNAL_CODEX_BIN by the isolated integration test.
let prompt='';process.stdin.setEncoding('utf8');process.stdin.on('data',s=>prompt+=s);
const send=x=>process.stdout.write(JSON.stringify(x)+'\n');
process.stdin.on('end',()=>{
  send({type:'thread.started',thread_id:'fixture'});send({type:'turn.started'});
  send({type:'item.completed',item:{id:'reasoning',type:'reasoning',text:'PRIVATE_REASONING_FIXTURE_MUST_NOT_APPEAR'}});
  if(prompt.includes('WAIT_FIXTURE')){setInterval(()=>{},1000);return;}
  setTimeout(()=>{
    if(prompt.includes('FAIL_FIXTURE')) {send({type:'turn.failed',error:{message:'Fixture failure: retry available'}});process.exitCode=1;return;}
    const text=prompt.includes('INVALID_FIXTURE')?'{}':JSON.stringify({panels:[
      {kind:'prose',source:'## A generated part\n\nA colored cube with volume 24.'},
      {kind:'code',source:'(-> (m/cube 2 3 4)\n    (m/color [0.2 0.5 0.7 1]))'}]});
    send({type:'item.completed',item:{id:'final',type:'agent_message',text}});send({type:'turn.completed'});
  },1800);
});
