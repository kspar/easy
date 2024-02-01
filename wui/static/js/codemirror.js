const codeMirrorInstance = new CodeMirror(
  document.querySelector("#codemirrordiv"),
  {
    lineNumbers: true,
    tabSize: 2,
    value: 'def function():\n\tprint("Hello, World")',
    mode: "python",
    theme: "cobalt",
  }
);

console.log(CodeMirror.MergeView);

let doc = `one
two
three
four
five`;
console.log(CodeMirror.basicSetup);
console.log(CodeMirror.EditorView);
console.log(CodeMirror.EditorState);
console.log(doc.replace(/t/g, "T") + "\nSix");
// console.log(CodeMirror.basicSetup);

let view = new CodeMirror.MergeView(document.querySelector("#mergeviewdiv"), {
  //   edit: CodeMirror.DiffView,
//   origLeft: doc,
  // extensions: basicSetup,

  value: doc.replace(/t/g, "T") + "\nSix",
  revertButtons: true,
  origRight: { doc: doc.replace(/t/g, "T") + "\nSix" },
  // extensions: [
  //   //   basicSetup,
  //   EditorView.editable.of(false),
  //   EditorState.readOnly.of(true),
  // ],
  showDifferences: false,
  collapseIdentical: true,
  connect: "align",
  allowEditingOriginals: true,
  ignoreWhitespace: true,
});

console.log(view);
