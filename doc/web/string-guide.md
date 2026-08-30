# The UI string guide

What `web/src/i18n/en.json` and `et.json` say, and why they say it that way. Written down after the
EZ-1785 audit, which read all 894 English strings and then all the Estonian ones, because the same
concept had acquired four names and nobody could have noticed key-by-key — you only see it by
grouping the vocabulary.

**Estonian is the default language** (`i18n.ts`: `localStorage.getItem('language') ?? 'et'`), and the
browser suite runs in English (`tests/support/harness.mjs`: `language = 'en'`). So Estonian is both
what most people read and the half no test exercises. When the two files disagree, assume Estonian is
the one that has been drifting unwatched.

Companion documents: `doc/web/ui-guide.md` (how it looks), `doc/web/ux-audit-log.md` (the evidence).

---

## One concept, one word

A concept rendered two ways across two screens is the failure mode this table exists to prevent.
**Adding a key? Find its concept here first.** If it is not here and it names a domain thing, add
the row.

| Concept | English | Estonian | Note |
|---|---|---|---|
| Automatic grading, as a whole | **tests** | **testid** | Never "auto-assessment", "automated check", "automated tests", "automaatkontroll". It had all four; the filters already said `Testidega`/`Testideta`, so the short word won |
| The tab where tests are configured | Tests | Testid | `library.tabAutoAssess` |
| Which grader runs them | Test framework | **Kontrollija** | TSL or Python Grader. Not "Testiraamistik" — a calque, and a developer's word in front of teachers |
| The tab where you try the exercise | Try it | **Katseta** | Not "Testimine"; it would sit beside "Testid" |
| The TSL visual builder tab | TSL | TSL | "TSL" now names the editor, not the format |
| The raw TSL spec tab | Spec | **Spetsifikatsioon** | The errors about it have always said *spetsifikatsioon* |
| A test passes | passes | **läbib** | Reserved for outcome. A run that merely ended *lõpetab* — see the trap below |
| The identity/login account | **Lahendus ID** | Lahendus ID | Never "Keycloak", never "your Lahendus account" (you are *in* Lahendus) |
| A person taking a course | student | **õpilane** | Not *tudeng*, which the app said twice against thirty-seven |
| The exercise library | Exercise library | **Ülesandekogu** | One word. The landing page said "Ülesannete kogu" and the nav disagreed |
| Open source | open source | **avatud lähtekood** | Not *vabavara* |
| Email | Email | **Email** | Not *e-post* |
| An exercise begun but unsolved | **in progress** | **pooleli** | Was "unsuccessful"/"nässu läinud" — a verdict, delivered minutes after submitting, and read aloud by the status icon |
| Making an exercise visible | Show | **Avalikusta** | English pairs Hide/Show; Estonian deliberately keeps the publish verb |
| The two date settings | Deadline / Closing time | Tähtaeg / Sulgemise aeg | There are no "soft and hard deadlines". The landing page invented them |
| Duplicating a thing | **Duplicate** | Loo koopia | Distinct from clipboard *Copy* / *Kopeeri*, which shared the word "Copy" until EZ-1785 |
| Calls a function (syntactic) | Calls a function | **kutsub välja** | |
| Runs a function (executes) | Run a function | **käivitab** | Parallel to "Käivitab programmi". These two were byte-identical in Estonian; a UT lecturer reported it (X-024) |

**Check:** `cd web && node tests/lint/strings.mjs`. One sanctioned exception:
`landing.terminalCaption` still reads *automaatkontroll*, because it is decorative text inside a
mock terminal rather than UI vocabulary.

## House rules

All of these are enforced by `node tests/lint/strings.mjs`, which reads **values** and not keys —
the first draft of it grepped whole lines and dutifully reported `courseColor` and
`autogradeAnalyzing` as American spellings in a file that had none.

| Rule | Why |
|---|---|
| **`…`**, never `...` | Typography. 23 strings used ASCII dots and 11 the character |
| A name in a confirmation is **bold**, never quoted | Survives a name containing a quote; `<Trans components={{ bold: <strong /> }} />` renders it |
| **British English** | Estonian higher education follows it, and it was already the majority |
| **Sentence case** everywhere | Already true; the file has no Title Case outside proper nouns |
| No full stop on a **fragment**; a stop on a **sentence** | Empty states are fragments. "No tests yet" not "No tests yet." |
| Emoticons on **empty states only**, never on errors | A shrug on a link a student cannot fix is the product shrugging at them |
| Counts get **`_one`/`_other`**, never `(s)` | Estonian needs it too, and "1 pairs" reads as a bug |
| Limits and sizes live **in code**, interpolated | A number in a string is two files from the thing that can contradict it. See `UPLOAD_LIMIT_MB` |
| Code literals keep **straight** quotes; field and type names take **curly** | `"type"` is JSON; `“{{name}}”` is prose |
| Both files are edited **together** | They are at parity and that is worth keeping true |

## Two traps this audit walked into

**A rename is only safe when the old string disappears.** Renaming the auto-assessment tab to "Tests"
left the TSL builder's own "Tests" tab in place, and every `getByRole('tab', { name: 'Tests' })` kept
matching — the wrong tab. The same shape bit the `Kutsub välja funktsiooni` split. A matcher that
still matches after a rename is worse than one that breaks, because nothing goes red. Anchor
matchers (`/^Testid$/`), and where a test depends on a specific *identity*, assert the consequence
too rather than the label.

**A word can mean more than it says.** `retryAutoassessDone` was translated "Testid on läbitud",
which in this file's own vocabulary means the tests *passed* — but the English says only "finished",
and the message fires whatever the result. It told teachers a failing re-run was fine. When
translating an outcome-adjacent string, check what the surrounding strings use the same verb for.

## Checks

```sh
# parity: same keys in both files, both directions
cd web && node -e "
const flat=(o,p='')=>Object.entries(o).flatMap(([k,v])=>typeof v==='object'?flat(v,p+k+'.'):[p+k]);
const a=new Set(flat(require('./src/i18n/en.json'))), b=new Set(flat(require('./src/i18n/et.json')));
console.log('en',a.size,'et',b.size);
console.log('missing in et:',[...a].filter(x=>!b.has(x)).join(', ')||'none');
console.log('missing in en:',[...b].filter(x=>!a.has(x)).join(', ')||'none');"
```

Interpolation and markup must match across locales — a `{{count}}` or a `<bold>` present in one file
and absent in the other is a rendering bug, not a translation choice. The audit's script for this is
worth re-deriving rather than trusting; it found zero mismatches at `6203f20b`, which is the number
to expect.

Orphaned keys: match each key against the whole tree rather than against `t('…')` alone — the TSL
editor composes keys at runtime (`t(\`landing.\${group}.\${featureKey}Title\`)`), so a naive sweep
reports about 160 false positives on top of the real ones. EZ-1785 removed 57 confirmed dead keys
this way.
