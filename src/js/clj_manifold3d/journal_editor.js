import {EditorState, Compartment, Prec, Transaction} from '@codemirror/state';
import {EditorView, keymap, drawSelection, highlightActiveLine} from '@codemirror/view';
import {defaultKeymap, history, historyKeymap, indentWithTab, isolateHistory} from '@codemirror/commands';
import {HighlightStyle, bracketMatching, indentOnInput, indentRange, indentUnit, ensureSyntaxTree, syntaxHighlighting, syntaxTree} from '@codemirror/language';
import {closeBrackets, closeBracketsKeymap} from '@codemirror/autocomplete';
import {tags} from '@lezer/highlight';
import {clojure, clojureLanguage} from '@nextjournal/lang-clojure';
import {vim, getCM, Vim} from '@replit/codemirror-vim';
import {EditorState as ProseState, Selection as ProseSelection} from 'prosemirror-state';
import {EditorView as ProseView} from 'prosemirror-view';
import {schema, defaultMarkdownParser, defaultMarkdownSerializer} from 'prosemirror-markdown';
import {baseKeymap, toggleMark, setBlockType, wrapIn} from 'prosemirror-commands';
import {history as proseHistory, undo, redo} from 'prosemirror-history';
import {keymap as proseKeymap} from 'prosemirror-keymap';
import {wrapInList, splitListItem, sinkListItem, liftListItem} from 'prosemirror-schema-list';
import {inputRules, textblockTypeInputRule, wrappingInputRule} from 'prosemirror-inputrules';

const isForm = n => n && !['LineComment', '⚠'].includes(n.name);
const children = n => { const out=[]; for(let c=n.firstChild;c;c=c.nextSibling) out.push(c); return out; };
export function forms(source) {
  return children(clojureLanguage.parser.parse(source).topNode).filter(isForm)
    .map(n=>({from:n.from,to:n.to,source:source.slice(n.from,n.to)}));
}

// Lezer knows about strings, reader macros and comments: delimiters are never
// located by scanning raw characters backwards.
export function evaluationRange(state, topLevel=false) {
  const selection=state.selection.main;
  if(!selection.empty) {
    const top=children(syntaxTree(state).topNode).find(n=>n.from<=selection.from && n.to>=selection.from);
    return {from:selection.from,to:selection.to,topFrom:top?.from||0};
  }
  const pos=selection.head, tree=syntaxTree(state), roots=children(tree.topNode).filter(isForm);
  let node=roots.find(n=>n.from<=pos && n.to>=pos) || [...roots].reverse().find(n=>n.to<=pos) || roots[0];
  if(!node) return null;
  const top=node;
  if(!topLevel && pos>=node.from && pos<=node.to) {
    let inner=tree.resolveInner(pos,-1);
    // At a closing delimiter, evaluate the closed form; on a scalar, the scalar.
    if([')',']','}'].includes(inner.name)) inner=inner.parent;
    if(['(', '[', '{'].includes(inner.name)) inner=inner.parent;
    if(inner.name==='StringContent') inner=inner.parent;
    if(inner.name!=='Program' && inner.name!=='LineComment') node=inner;
    while(node.parent && node.parent.name!=='Program' && node.parent.to===node.to && pos===node.to) node=node.parent;
  }
  return {from:node.from,to:node.to,topFrom:top.from};
}

function collectionAt(state) {
  const range=state.selection.main;
  let from=range.from,to=range.to;
  while(from<to && /\s/.test(state.sliceDoc(from,from+1))) from++;
  while(to>from && /\s/.test(state.sliceDoc(to-1,to))) to--;
  let node=syntaxTree(state).resolveInner(range.empty?range.head:from,range.empty?-1:1);
  while(node && (!['List','Vector','Map'].includes(node.name) || (!range.empty && node.to<to))) node=node.parent;
  if(!node)return null;
  const container=node;
  // Keep reader prefixes attached to the collection, including when barfing
  // backwards. Otherwise #(a b) could accidentally turn into #a (b).
  while(node.parent && ['Set','Quote','Deref','Meta','AnonymousFunction','Discard'].includes(node.parent.name)) node=node.parent;
  return {node,container};
}
export function structural(view, action) {
  const state=view.state, collection=collectionAt(state); if(!collection) return false;
  const {node,container}=collection;
  const nodes=children(container), open={from:node.from,to:nodes[0].to}, close=nodes[nodes.length-1];
  if(!open || !close || ![')',']','}'].includes(close.name)) return false;
  const text=(n)=>state.doc.sliceString(n.from,n.to), forward=action.endsWith('forward');
  let changes;
  if(action.startsWith('slurp')) {
    let wrapper=node;
    while(wrapper.parent && wrapper.parent.name!=='Program' && ['Quote','Deref','Meta','AnonymousFunction','Discard'].includes(wrapper.parent.name)) wrapper=wrapper.parent;
    let sibling=forward?wrapper.nextSibling:wrapper.prevSibling;
    while(sibling && sibling.name==='LineComment') sibling=forward?sibling.nextSibling:sibling.prevSibling;
    if(!sibling || !isForm(sibling) || ['(',')','[',']','{','}'].includes(sibling.name)) return false;
    const delimiter=forward?close:open;
    const at=forward?sibling.to:sibling.from;
    const left=state.sliceDoc(delimiter.from-1,delimiter.from),right=state.sliceDoc(delimiter.to,delimiter.to+1);
    const separator=left && right && !/[\s([{]/.test(left) && !/[\s)\]}]/.test(right)?' ':'';
    changes=[{from:delimiter.from,to:delimiter.to,insert:separator},{from:at,insert:text(delimiter)}];
  } else {
    const elements=nodes.slice(1,-1).filter(isForm); if(!elements.length) return false;
    if(forward) {
      const last=elements[elements.length-1];
      // Close before intervening comments, never inside a line comment.
      const at=elements.length>1?elements[elements.length-2].to:open.to;
      const separator=at===last.from?' ':'';
      changes=[{from:at,insert:text(close)+separator},{from:close.from,to:close.to,insert:''}];
    } else {
      const first=elements[0]; let at=first.to;
      while(at<close.from && /\s/.test(state.doc.sliceString(at,at+1))) at++;
      const separator=at===first.to?' ':'';
      changes=[{from:open.from,to:open.to,insert:''},{from:at,insert:separator+text(open)}];
    }
  }
  changes.sort((a,b)=>a.from-b.from);
  const moved=state.update({changes});
  const from=moved.changes.mapPos(Math.min(node.from,...changes.map(c=>c.from)),-1);
  const to=moved.changes.mapPos(Math.max(node.to,...changes.map(c=>c.to??c.from)),1);
  ensureSyntaxTree(moved.state,to,100);
  const indentation=[];
  indentRange(moved.state,from,to).iterChanges((from,to,_from,_to,insert)=>{
    // The language's default indenter returns column zero inside strings.
    // Literal whitespace is content, not code formatting: never rewrite it.
    let token=syntaxTree(moved.state).resolveInner(from,1);
    while(token){
      if(['String','RegExp'].includes(token.name) && token.from<from)return;
      token=token.parent;
    }
    indentation.push({from,to,insert});
  });
  const formatted=moved.state.changes(indentation);
  // Delimiter movement + indentation is one transaction and one undo step.
  view.dispatch({changes:moved.changes.compose(formatted),selection:moved.newSelection.map(formatted),
    annotations:isolateHistory.of('full'),userEvent:'input.structural'});
  view.focus();return true;
}

const highlighting=HighlightStyle.define([
  {tag:tags.comment,color:'#7c837d',fontStyle:'italic'},
  {tag:[tags.keyword,tags.atom],color:'#886130'},
  {tag:[tags.string,tags.regexp],color:'#47745d'},
  {tag:tags.number,color:'#87608d'},
  {tag:tags.definition(tags.variableName),color:'#276678'},
  {tag:tags.invalid,textDecoration:'underline wavy #c35142'}
]);

// Trim unchanged context from a literal edit before dispatching it. This keeps
// selections, decorations and unaffected syntax nodes in their original place.
export function textChange(before,after,offset=0) {
  let from=0,endBefore=before.length,endAfter=after.length;
  while(from<endBefore&&from<endAfter&&before[from]===after[from])from++;
  while(endBefore>from&&endAfter>from&&before[endBefore-1]===after[endAfter-1]){endBefore--;endAfter--;}
  return {from:offset+from,to:offset+endBefore,insert:after.slice(from,endAfter)};
}
function patchChange(source,{before,after}) {
  const at=source.indexOf(before);
  if(at<0||(!before&&source)|| (before&&source.indexOf(before,at+1)>=0))throw Error('Missing or ambiguous patch fragment');
  return textChange(before,after,at);
}
function proseChange(view,next,preview=false) {
  const before=view.state.doc,from=before.content.findDiffStart(next.content);
  if(from==null)return;
  let {a:to,b:end}=before.content.findDiffEnd(next.content);
  const overlap=from-Math.min(to,end);
  if(overlap>0){to+=overlap;end+=overlap;}
  const tr=view.state.tr.replace(from,to,next.slice(from,end));
  view.dispatch(preview?tr.setMeta('addToHistory',false):tr);
}

export function createCodeEditor(parent, options={}) {
  const vimMode=new Compartment(),previewMode=new Compartment(); let remote=false,previewState=null;
  let previewEdits=[],previewChanges=null,finishedPreview=null;
  const evaluate=(top=false)=>{if(previewState)return true;const range=evaluationRange(view.state,top); if(range) options.onEvaluate?.({...range,source:view.state.sliceDoc(range.from,range.to)});return true;};
  const split=()=>{if(!previewState)options.onSplit?.(view.state.selection.main.head);return true;};
  const command=name=> previewState?true:name==='form'?evaluate():name==='top'?evaluate(true):name==='split'?split():name==='block'?(options.onRun?.(),true):structural(view,name);
  const shortcuts=[
    {key:'Mod-Enter',run:()=>evaluate()}, {key:'Mod-e',run:()=>evaluate()},
    {key:'Mod-Shift-Enter',run:split},
    {key:'Mod-Shift-e',run:()=>evaluate(true)},
    {key:'Shift-Enter',run:()=>command('block')},
    {key:'Mod-Alt-Enter',run:()=>{if(!previewState)options.onRunAll?.();return true;}},
    {key:'Ctrl-Alt-ArrowRight',run:v=>structural(v,'slurp-forward')},
    {key:'Ctrl-Alt-ArrowLeft',run:v=>structural(v,'barf-forward')},
    {key:'Ctrl-Alt-Shift-ArrowLeft',run:v=>structural(v,'slurp-backward')},
    {key:'Ctrl-Alt-Shift-ArrowRight',run:v=>structural(v,'barf-backward')}
  ];
  const view=new EditorView({parent,state:EditorState.create({doc:options.value||'',extensions:[
    Prec.highest(EditorView.domEventHandlers({keydown:(event,view)=>{
      const cm=getCM(view);
      if(!cm?.state.vim?.visualMode || event.ctrlKey || event.metaKey || event.altKey || !['>','<'].includes(event.key))return false;
      structural(view,event.key==='>'?'barf-forward':'slurp-forward');
      Vim.handleKey(cm,'<Esc>');
      event.preventDefault();return true;
    }})),
    Prec.highest(keymap.of(shortcuts)), vimMode.of(options.vim?vim():[]),previewMode.of([]),
    EditorState.changeFilter.of(()=>!previewState||remote),
    clojure(), history(), drawSelection(), highlightActiveLine(), indentOnInput(), indentUnit.of('  '), bracketMatching(),
    clojureLanguage.data.of({closeBrackets:{brackets:['(','[','{','"']}}),closeBrackets(),
    keymap.of([...closeBracketsKeymap,...defaultKeymap,...historyKeymap,indentWithTab]),
    syntaxHighlighting(highlighting), EditorView.lineWrapping,
    EditorView.theme({'&':{background:'transparent'},'&.cm-focused':{outline:'none'},'.cm-scroller':{fontFamily:'"SFMono-Regular",Consolas,monospace',fontSize:'13px',lineHeight:'1.8'},'.cm-content':{padding:'12px 0'},'.cm-line':{padding:'0 18px'},'.cm-activeLine':{background:'#62897608'},'.cm-matchingBracket':{background:'#a4c5b84d'}}),
    EditorView.contentAttributes.of({'aria-label':options.label||'Clojure code',spellcheck:'false'}),
    EditorView.domEventHandlers({focus:()=>{options.onFocus?.();return false;}}),
    EditorView.updateListener.of(update=>{if(update.docChanged&&!remote){finishedPreview=null;options.onChange?.(update.state.doc.toString());}})
  ]})});
  return {getValue:()=>view.state.doc.toString(),
    setValue(value){remote=true;try{const current=view.state.doc.toString();if(value!==current){view.dispatch({changes:finishedPreview?.source===value&&finishedPreview.base===current?finishedPreview.changes:textChange(current,value),annotations:isolateHistory.of('full')});finishedPreview=null;}}finally{remote=false;}},
    setPreview(value,patches=[]){
      remote=true;
      try{
        if(value==null){if(previewState){finishedPreview={base:previewState.doc.toString(),source:view.state.doc.toString(),changes:previewChanges};const original=previewState;previewState=null;previewEdits=[];view.setState(original);}return;}
        // Reconnect/revised request prefixes start from the saved original.
        if(previewState&&(patches.length<previewEdits.length||previewEdits.some((e,i)=>e.before!==patches[i]?.before||e.after!==patches[i]?.after))){view.setState(previewState);previewState=null;previewEdits=[];}
        if(!previewState){finishedPreview=null;previewState=view.state;previewChanges=view.state.changes([]);view.dispatch({effects:previewMode.reconfigure([EditorState.readOnly.of(true),EditorView.editable.of(false)])});}
        for(const edit of patches.slice(previewEdits.length)){
          const tr=view.state.update({changes:patchChange(view.state.doc.toString(),edit),annotations:Transaction.addToHistory.of(false)});
          previewChanges=previewChanges.compose(tr.changes);view.update([tr]);
        }
        previewEdits=patches.slice();
      }finally{remote=false;}
    },
    setVim(value){const effects=vimMode.reconfigure(value?vim():[]);if(previewState)previewState=previewState.update({effects}).state;view.dispatch({effects});},
    select(from,to=from){view.dispatch({selection:{anchor:from,head:to}});},
    command,focus:()=>view.focus(),destroy:()=>view.destroy()};
}

export function createProseEditor(parent, options={}) {
  let remote=false,previewState=null;
  let canonical=options.value||'',previewSource=null,previewEdits=[];
  const commands={bold:toggleMark(schema.marks.strong),italic:toggleMark(schema.marks.em),
    code:toggleMark(schema.marks.code),heading:setBlockType(schema.nodes.heading,{level:2}),
    paragraph:setBlockType(schema.nodes.paragraph),list:wrapInList(schema.nodes.bullet_list),
    quote:wrapIn(schema.nodes.blockquote)};
  const view=new ProseView(parent,{state:ProseState.create({doc:defaultMarkdownParser.parse(options.value||''),plugins:[
    proseHistory(),inputRules({rules:[
      textblockTypeInputRule(/^(#{1,3})\s$/,schema.nodes.heading,m=>({level:m[1].length})),
      wrappingInputRule(/^\s*([-+*])\s$/,schema.nodes.bullet_list),
      wrappingInputRule(/^\s*>\s$/,schema.nodes.blockquote)]}),
    proseKeymap({'Mod-Enter':()=>{if(!previewState)options.onPrompt?.();return true;},
      'Mod-End':(state,dispatch)=>{dispatch(state.tr.setSelection(ProseSelection.atEnd(state.doc)).scrollIntoView());return true;},
      'Mod-Home':(state,dispatch)=>{dispatch(state.tr.setSelection(ProseSelection.atStart(state.doc)).scrollIntoView());return true;},
      'Mod-b':commands.bold,'Mod-i':commands.italic,'Mod-`':commands.code,
      'Mod-Alt-2':commands.heading,'Mod-Alt-0':commands.paragraph,'Mod-Shift-8':commands.list,
      'Mod-z':undo,'Mod-Shift-z':redo,'Enter':splitListItem(schema.nodes.list_item),
      'Tab':sinkListItem(schema.nodes.list_item),'Shift-Tab':liftListItem(schema.nodes.list_item)}),proseKeymap(baseKeymap)]}),
    attributes:{class:'journal-prose',role:'textbox','aria-label':'Journal prose'},
    handleDOMEvents:{focus:()=>{options.onFocus?.();return false;}},
    dispatchTransaction(tr){if(previewState&&!remote&&tr.docChanged)return;view.updateState(view.state.apply(tr));if(tr.docChanged&&!remote){canonical=defaultMarkdownSerializer.serialize(view.state.doc);options.onChange?.(canonical);}}
  });
  return {command(name){if(previewState)return false;view.focus();return commands[name]?.(view.state,view.dispatch,view);},
    getValue:()=>defaultMarkdownSerializer.serialize(view.state.doc),
    setValue(value){remote=true;try{canonical=value;proseChange(view,defaultMarkdownParser.parse(value));}finally{remote=false;}},
    setPreview(value,patches=[]){
      remote=true;
      try{
        if(value==null){if(previewState){view.updateState(previewState);previewState=null;previewEdits=[];view.setProps({editable:()=>true});}return;}
        if(previewState&&(patches.length<previewEdits.length||previewEdits.some((e,i)=>e.before!==patches[i]?.before||e.after!==patches[i]?.after))){view.updateState(previewState);previewState=null;previewEdits=[];}
        if(!previewState){previewState=view.state;previewSource=canonical;view.setProps({editable:()=>false});}
        for(const edit of patches.slice(previewEdits.length)){
          const {from,to,insert}=patchChange(previewSource,edit);
          previewSource=previewSource.slice(0,from)+insert+previewSource.slice(to);
          proseChange(view,defaultMarkdownParser.parse(previewSource),true);
        }
        previewEdits=patches.slice();
      }finally{remote=false;}
    },
    focus:()=>view.focus(),destroy:()=>view.destroy()};
}
