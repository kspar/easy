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

let doc = `def function():
  for i in range(10):
    print("Hello, World")`;
let doc_muudatud =
  doc.replace(/10/g, "22") + "\nsome_variable = 5\nprint(some_variable)";

let view = new CodeMirror.MergeView(document.querySelector("#mergeviewdiv"), {
  origLeft: doc,
  value: doc_muudatud,
  mode: "python",
  // Kui tahta lisada 3. võrdlust, siis tuleb lisada origRight
  // origRight: doc_muudatud,
  // revertButtons: true,
  // showDifferences: true,
  // collapseIdentical: true,
  // connect: "align",
  // allowEditingOriginals: true,
  // ignoreWhitespace: true,
  // syntaxHighlightDeletions: true,
  // gutter: true,
  // lineNumbers: true,
  // addModeClass: true,
  // spellcheck: true,
  // lineWrapping: true,
  // indentWithTabs: true,
  // rtlMoveVisually: true,
  // theme: "cobalt",
});
