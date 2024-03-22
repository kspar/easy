# CodeMirror kasutamine Lahenduse wuis

> Need märkused on tehtud Code Mirror 5->6 migreerimise jälgimiseks ning selleks, et tulevikus oleks lihtsam hallata. 

## Referentsid:

- [Tutorial puhtas HTML'is katsetamiseks](https://thecodebarbarian.com/building-a-code-editor-with-codemirror.html)
- [Find the CDN package](https://cdnjs.com/libraries/codemirror)
- [What is a CDN?](https://www.cloudflare.com/learning/cdn/what-is-a-cdn/)
- [Teemade valik](https://thememirror.net)

## Kuidas kirjutada Python'i editorit
Tuleb mängida `value` võtmega kui tahta midagi automaatselt sisestada:
```javascript
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
```

## Kuidas programmeerida MergeView editorit
Väga oluline on importida teeki `diff-match-patch`.
Minimum code to launch the mergeview:
```javascript
let doc = `def function():
  for i in range(10):
    print("Hello, World")`;
let doc_muudatud =
  doc.replace(/10/g, "22") + "\nsome_variable = 5\nprint(some_variable)";

let view = new CodeMirror.MergeView(document.querySelector("#mergeviewdiv"), {
  origLeft: doc,
  value: doc_muudatud,
  mode: "python",
});
```
Ma ei leidnud hea dokumentatsiooni kõik omaduste kohta. 
Ma arvan, et parema kontrollimiseks on vaja kirjutada Node.js ja valmistada [Bundle'it](https://codemirror.net/examples/bundle/)
