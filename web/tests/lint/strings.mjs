/**
 * Lexical checks over string VALUES in en.json and et.json.
 *
 * Values, not keys — the keys are named in English, so a naive grep for "color" or "analyz"
 * matches `courseColor` and `autogradeAnalyzing` and reports a file that is already correct.
 * The first version of this did exactly that.
 *
 * The rules are EZ-1785's, and doc/web/string-guide.md explains each one.
 *
 *     cd web && node tests/lint/strings.mjs
 */
import fs from 'node:fs'
const flat = (o, p = '') =>
  Object.entries(o).flatMap(([k, v]) => (typeof v === 'object' ? flat(v, p + k + '.') : [[p + k, v]]))
const en = flat(JSON.parse(fs.readFileSync('src/i18n/en.json', 'utf8')))
const et = flat(JSON.parse(fs.readFileSync('src/i18n/et.json', 'utf8')))

// [label, entries, /pattern/, allowed keys]
const RULES = [
  ['ET terms the audit retired', et, /automaatkontroll|tudeng|vabavara|e-post|keycloak/i,
    ['landing.terminalCaption']],
  // Both languages, unlike the rule above: "Lahendus ID" was the same three characters in each, and
  // it named a product nobody outside this repo had heard of. Retired in favour of Lahendus user /
  // Lahenduse kasutaja — a guide row without a detector is a preference, not a rule.
  ['the retired "Lahendus ID"', [...en, ...et], /Lahendus\s?ID/i, []],
  ['ASCII ellipsis', [...en, ...et], /\.\.\./, []],
  ['US spelling', en, /\b(color|analyz|organiz|recogniz)/i, []],
  ['"(s)" pluralisation', [...en, ...et], /\w\(s\)/, []],
  ['a size baked into copy', [...en, ...et], /\d+\s?(MB|KB|GB)\b/, []],
]

let bad = 0
for (const [label, entries, re, allow] of RULES) {
  const hits = entries.filter(([k, v]) => re.test(v) && !allow.includes(k))
  if (hits.length) {
    bad += hits.length
    console.log(`FAIL  ${label}`)
    for (const [k, v] of hits) console.log(`        ${k}  ${JSON.stringify(v).slice(0, 90)}`)
  } else {
    console.log(`ok    ${label}${allow.length ? ` (${allow.length} sanctioned exception)` : ''}`)
  }
}

// Emoticons are allowed, but only on empty states — never on an error.
const emo = [...en, ...et].filter(([, v]) => /:\)|:\(|ツ/.test(v))
console.log(`\nemoticons (${emo.length}) — each must be an empty state, not an error:`)
for (const [k, v] of emo) console.log(`        ${k}  ${JSON.stringify(v).slice(0, 70)}`)

process.exit(bad ? 1 : 0)
