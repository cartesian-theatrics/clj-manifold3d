// Test the production editor bundle without touching any journal/database.
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs/promises');
const {chromium}=require('playwright');

(async()=>{
  const bundle=await fs.readFile('public/journal/js/editor.js');
  const server=http.createServer((req,res)=>{
    res.setHeader('Content-Type',req.url==='/editor.js'?'text/javascript':'text/html');
    res.end(req.url==='/editor.js'?bundle:'<!doctype html><body></body>');
  });
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  let browser;
  try{
    browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH});
    const page=await browser.newPage();
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    const result=await page.evaluate(async()=>{
      const {createCodeEditor,createProseEditor,textChange}=await import('/editor.js');
      const root=document.body.appendChild(document.createElement('div'));
      const original=';; keep head\n(def width 2)\n;; keep middle\n(def color :red)\n;; keep tail';
      let writes=0;
      const code=createCodeEditor(root,{value:original,onChange:()=>writes++});
      const unchanged=root.querySelectorAll('.cm-line');
      const patches=[{before:'width 2',after:'width 5'},{before:':red',after:':blue'}];
      const first=original.replace('width 2','width 5');
      const final=first.replace(':red',':blue');
      code.select(original.indexOf('middle'));
      code.setPreview(first,patches.slice(0,1));
      const firstValue=code.getValue();
      code.setPreview(final,patches);
      code.setPreview(final,patches); // repeated polling must not reapply edits
      const finalValue=code.getValue();
      const retained=[0,2,4].every(i=>unchanged[i]===root.querySelectorAll('.cm-line')[i]);
      const previewWrites=writes;
      code.setPreview(null);const restored=code.getValue();
      // Committing the composed range changes is one undoable action.
      code.setValue(final);code.focus();
      window.deltaEditor=code;

      const proseRoot=document.body.appendChild(document.createElement('div'));
      const proseSource='# Heading\n\n**Bold** stays.\n\nValue 2.\n\nTail stays.';
      const prose=createProseEditor(proseRoot,{value:proseSource});
      const heading=proseRoot.querySelector('h1'),bold=proseRoot.querySelector('strong'),tail=proseRoot.querySelector('p:last-child');
      const prosePatch={before:'Value 2',after:'Value 3'};
      prose.setPreview(proseSource.replace('Value 2','Value 3'),[prosePatch]);
      const proseRetained=heading===proseRoot.querySelector('h1')&&bold===proseRoot.querySelector('strong')&&tail===proseRoot.querySelector('p:last-child');
      const proseValue=prose.getValue();
      prose.setPreview(null);const proseRestored=prose.getValue();
      prose.destroy();proseRoot.remove();
      return {change:textChange('(def width 2)','(def width 5)'),original,first,final,firstValue,finalValue,retained,previewWrites,restored,proseRetained,proseValue,proseRestored};
    });
    assert.deepEqual(result.change,{from:11,to:12,insert:'5'});
    assert.equal(result.firstValue,result.first);assert.equal(result.finalValue,result.final);
    assert.ok(result.retained,'Unchanged code lines keep their actual DOM nodes');
    assert.equal(result.previewWrites,0);assert.equal(result.restored,result.original);
    assert.ok(result.proseRetained,'Unchanged rich-text nodes keep their identity');
    assert.match(result.proseValue,/Value 3/);assert.match(result.proseRestored,/Value 2/);
    await page.keyboard.press('ControlOrMeta+z');
    assert.equal(await page.evaluate(()=>window.deltaEditor.getValue()),result.original);
    await page.keyboard.press('ControlOrMeta+Shift+z');
    assert.equal(await page.evaluate(()=>window.deltaEditor.getValue()),result.final);
    assert.deepEqual(errors,[]);
    console.log('PASS: minimal editor ranges, unchanged DOM nodes, sequential/idempotent patches, prose formatting, rollback, composed undo/redo');
  }finally{await browser?.close();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
