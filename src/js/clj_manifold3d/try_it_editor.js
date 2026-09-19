import {EditorState} from '@codemirror/state';
import {EditorView, drawSelection, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers} from '@codemirror/view';
import {defaultKeymap, history, historyKeymap, indentWithTab} from '@codemirror/commands';
import {HighlightStyle, bracketMatching, indentOnInput, indentUnit, syntaxHighlighting} from '@codemirror/language';
import {closeBrackets, closeBracketsKeymap} from '@codemirror/autocomplete';
import {tags} from '@lezer/highlight';
import {clojure, clojureLanguage} from '@nextjournal/lang-clojure';

const theme = EditorView.theme({
  '&': {height: '100%', backgroundColor: 'transparent', color: '#d3dfca'},
  '&.cm-focused': {outline: 'none'},
  '.cm-scroller': {overflow: 'auto', fontFamily: '"SFMono-Regular", Consolas, "Liberation Mono", monospace', fontSize: '12px', lineHeight: '1.95', scrollbarColor: '#45554c transparent'},
  '.cm-content': {padding: '19px 0', caretColor: '#ecc193'},
  '.cm-line': {padding: '0 16px 0 5px'},
  '.cm-gutters': {backgroundColor: '#202828', color: '#748b7e', border: 'none'},
  '.cm-lineNumbers .cm-gutterElement': {minWidth: '39px', padding: '0 9px 0 8px'},
  '.cm-activeLine, .cm-activeLineGutter': {backgroundColor: '#ffffff05'},
  '.cm-cursor, .cm-dropCursor': {borderLeftColor: '#ecc193'},
  '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {backgroundColor: '#455a4c'},
  '&.cm-focused .cm-matchingBracket': {backgroundColor: '#e5ad7d30', outline: '1px solid #e5ad7d77'},
  '&.cm-focused .cm-nonmatchingBracket': {backgroundColor: '#df745540'},
  '.tok-comment': {color: '#92a69a', fontStyle: 'italic'},
  '.tok-keyword': {color: '#e8b788'},
  '.tok-atom': {color: '#87c8d0'},
  '.tok-number': {color: '#d3afdc'},
  '.tok-string': {color: '#b6d792'},
  '.tok-definition': {color: '#ecdaab'},
  '.tok-invalid': {textDecoration: 'underline wavy #e89b87'}
}, {dark: true});

const highlighting = HighlightStyle.define([
  {tag: tags.comment, class: 'tok-comment'},
  {tag: tags.keyword, class: 'tok-keyword'},
  {tag: [tags.atom, tags.null], class: 'tok-atom'},
  {tag: tags.number, class: 'tok-number'},
  {tag: [tags.string, tags.regexp], class: 'tok-string'},
  {tag: tags.definition(tags.variableName), class: 'tok-definition'},
  {tag: tags.invalid, class: 'tok-invalid'}
]);

// A small string-keyed bridge keeps CodeMirror outside Closure's property renaming.
// All evaluation and app state still live in ClojureScript.
export function createEditor(parent, {onChange, onRun}) {
  const extensions = [
    lineNumbers(), highlightActiveLineGutter(), highlightActiveLine(), drawSelection(),
    history(), clojure(), indentUnit.of('  '), indentOnInput(), bracketMatching(),
    // Apostrophes are Clojure reader quotes, not paired string delimiters.
    clojureLanguage.data.of({closeBrackets: {brackets: ['(', '[', '{', '"']}}),
    closeBrackets(),
    keymap.of([
      {key: 'Mod-Enter', run: () => { onRun(); return true; }},
      ...closeBracketsKeymap, ...defaultKeymap, ...historyKeymap, indentWithTab
    ]),
    syntaxHighlighting(highlighting), theme,
    EditorView.contentAttributes.of({'aria-label': 'ClojureScript code', spellcheck: 'false', autocapitalize: 'off', autocorrect: 'off'}),
    EditorView.updateListener.of(update => { if (update.docChanged) onChange(); })
  ];
  const view = new EditorView({parent, state: EditorState.create({extensions})});
  return {
    getValue: () => view.state.doc.toString(),
    // Switching examples resets undo history; it must not restore another example.
    setValue: doc => view.setState(EditorState.create({doc, extensions})),
    focus: () => view.focus(),
    destroy: () => view.destroy()
  };
}
