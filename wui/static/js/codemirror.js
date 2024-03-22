const codeMirrorInstance = new CodeMirror(
  document.querySelector("#codemirrordiv"),
  {
    lineNumbers: true,
    tabSize: 4,
    value: 'def function():\n\tprint("Hello, World")',
    mode: "python",
    theme: "cobalt",
    allowDropFileTypes: ["text/plain", "text/x-python"],
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


// Oneline editor:
const oneLineInstance = new CodeMirror(document.querySelector("#onelinediv"), {
  lineNumbers: false,
  tabSize: 2,
  value: "",
  mode: "text",
});

oneLineInstance.setSize(400, oneLineInstance.defaultTextHeight() + 3 * 4);
// 200 is the preferable width of text field in pixels,
// 4 is default CM padding (which depends on the theme you're using)

// now disallow adding newlines in the following simple way
oneLineInstance.on("beforeChange", function (instance, change) {
  var newtext = change.text.join("").replace(/\n/g, ""); // remove ALL \n !
  change.update(change.from, change.to, [newtext]);
  return true;
});


// Tabs example

var editors = document.getElementsByClassName("editor");
for (var i = 0; i < editors.length; i++) {
  var self = editors[i];
  var editor = new CodeMirror.fromTextArea(self, {
    mode: i ? "python" : "javascript",
    lineNumbers: true,
    autoRefresh: true,
  });
  editor.save();
}

var tabs = document.querySelectorAll(".tab");
document
  .querySelectorAll('.tab-pane[data-pane="1"]')[0]
  .classList.remove("active");
for (var i = 0; i < tabs.length; i++) {
  var self = tabs[i];
  self.addEventListener("click", function () {
    var data = this.getAttribute("data-tab");
    document.querySelectorAll(".tab-pane.active")[0].classList.remove("active");
    document
      .querySelectorAll('.tab-pane[data-pane="' + data + '"]')[0]
      .classList.add("active");
    document.querySelectorAll(".tab.active")[0].classList.remove("active");
    this.classList.add("active");
  });
}
