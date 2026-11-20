# Similarity (plagiarism detection)

`POST /v2/exercises/{exerciseId}/similarity` lists the most similar pairs among an exercise's
submissions. How it compares them follows from the exercise's language:

| Language | Method | What it means for a score |
| --- | --- | --- |
| Python | [JPlag](https://github.com/jplag/jplag): Greedy String Tiling over the code's tokens | Renaming every variable, reformatting, adding comments or blank lines does not lower it. |
| SQL, anything else | Edit distance: how much of two whole texts lines up character by character (fuzzywuzzy's `ratio`, which the check always reported) | Renaming and reformatting lower it, so a disguised copy can score low. |

The language is the grader's where it runs only one — Silmused is SQL, whatever the solution file is
called, since every new exercise starts as `lahendus.py` — and otherwise the solution file's extension:
`.py` is Python, `.sql` SQL, everything else text. There is no setting. A Python exercise whose answers
turn out mostly not to be Python — prose in a `lahendus.py` — is compared by JPlag word by word instead,
and the page says so: read as Python, the parser's error recovery made each line of prose one identical
token, and unrelated essays paired at 90–100%.

## How it is put together

Everything is in `core/ems/service/code/similarity/`; the endpoint is `TeacherCheckSimilarity.kt`, and
`TeacherReadSimilarityProgress.kt` answers the page's progress bar.

| File | What it is |
| --- | --- |
| `SimilarityService.kt` | One comparison at a time, its limits, the method for the language, identical submissions grouped, the outcome. |
| `PairRanking.kt` | Compares every pair worth comparing and keeps the best, by two orders. |
| `SimilarityWorkers.kt` | The threads a comparison runs on, and its deadline. |
| `SimilarityProvider.kt` | What a method owes the service: read a cohort, then score any two of it. |
| `JPlagProvider.kt` | JPlag: tokens from the frontends, JPlag's submission set, one tiling per pair. |
| `EditDistanceProvider.kt`, `EditDistance.kt` | Edit distance, bit-parallel. |
| `PythonFrontend.kt`, `BudgetedParserATNSimulator.kt` | Python's tokens: JPlag's ANTLR grammar and listener, run from a string, with a bounded parse. |
| `TextFrontend.kt` | Words, one token type per distinct word. |
| `SimilarityLanguage.kt` | The languages, and how an exercise's is decided. |

## Solutions never touch a filesystem

JPlag's documented entry point takes directories: every read goes through `de.jplag.util.FileUtils`,
which uses `FileInputStream` and `FileReader` on the path string, so no `File` subclass or custom file
system can intercept it. Using it as documented means writing student code to disk.

Instead only JPlag's *source of tokens* is replaced. `PythonFrontend` subclasses JPlag's
`PythonParserAdapter` to run its grammar and listener from a string, and
`de/jplag/antlr/InMemoryTokenCollectorAccess.kt` reaches the two package-private classes that turn a
parse tree into JPlag tokens — a split package, legal on the Boot jar's plain classpath. `JPlagProvider`
hands the tokens to JPlag through a `Language` whose `parse()` returns them, and JPlag's `SubmissionSet`
and tiling run on them as usual. The `File`s it is given are identifiers that are never opened. (`spring-boot-devtools` would load core under another class loader and turn that access
into an `IllegalAccessError`.)

The pairs are not compared by JPlag's driver, which compares every pair up front, waits up to a day, and
cannot leave a student's own pairs out; `PairRanking` runs one tiling per pair instead.

## What is compared with what

- **A student's own submissions are never paired.** Without ids, the endpoint compares every attempt;
  fifteen resubmissions would otherwise fill the list with self-pairs at 100%.
- **One text handed in by three or more students is one group**, compared once and listed once: twelve
  copies of the starter code were 66 rows at 100%. Two identical submissions stay a pair. Identical is
  the same text; a copy changed only in its spacing is a pair at 100%.
- **The best pairs by two orders are kept**: the 100 that share the most code (JPlag's matched tokens)
  and the 100 with the highest score, so the page's threshold, which filters on the score, finds every
  pair above it a list by score would hold. For edit distance the two are one order. Pairs under half a
  percent are dropped. `truncated` says when more scored than came back.
- **What cannot be compared is listed with a reason**, never silently left out: `TOO_SMALL` (fewer
  tokens than the shortest match, or blank), `TOO_LARGE`, `TOO_COMPLEX`, `TOO_REPETITIVE`, `CANNOT_PARSE`.

## Limits

A comparison holds every solution and token stream on core's heap, and neither a parse nor a tiling can
be interrupted once started, so each piece of work has a bound of its own.

| Limit | Value | What it stops |
| --- | --- | --- |
| `easy.core.similarity.max-submissions` | 500 | A cohort larger than this is refused (`TOO_MANY_SUBMISSIONS`) before any solution is loaded. |
| `easy.core.similarity.timeout-sec` | 120 | Read before each solution is tokenised and before each pair; past it the check ends with `SIMILARITY_TIMEOUT`. |
| One check at a time | 1 | A check while another runs is refused at once (`SIMILARITY_BUSY`): two large cohorts at once is the realistic way to exhaust the heap. |
| Lexical tokens per solution | 10,000 | About 35 KB of code; above it `TOO_LARGE`. Comments do not count, so padding cannot take a copy out. |
| Repetition per solution | Σc² ≤ 1,000,000 | Over the counts c of each run of shortest-match length: what makes one tiling slow. Above it `TOO_REPETITIVE`. |
| Python parse | brackets ≤ 200 deep; 2 s per parse and per tree walk; 5,000 new prediction states | Inputs valid to CPython that held the parser for minutes, or grew its shared cache by hundreds of MB. `TOO_COMPLEX`. |
| Edit distance | 20,000 characters | A pair costs \|a\|·\|b\|/64 steps; above it `TOO_LARGE`. |

The two keys have defaults in code, so a deploy needs no configuration change; `application.yaml.sample` lists them. The work runs on half the
cores, on threads with 64 MB stacks: the parsers are recursive descent, and on the JVM's default 1 MB
code CPython accepts overflowed.

## Tuning

The shortest run of tokens that counts as a match is **6** for Python and text alike (`MIN_TOKEN_MATCH`
in `JPlagProvider.kt`), where JPlag's default is 12. Measured on three exercise sizes, each with independently written
solutions and one copy with every name changed:

| shortest match | ~20-line exercise: copy / best honest | 3-line exercise: worst honest | ~9-line exercise: copy |
| --- | --- | --- | --- |
| 6 *(shipped)* | 100% / 58% | 100% | 100% |
| 10 | 100% / 58% | dropped as `TOO_SMALL` | **0%** |
| 12 *(JPlag default)* | 100% / 58% | dropped as `TOO_SMALL` | dropped as `TOO_SMALL` |

No value separates copying from honest rewriting, and none makes a three-line exercise safe without making
a nine-line one undetectable; 6 keeps short real exercises covered.

## Not built

Java, which needs javac in memory, and SQL by its tokens, which needs a lexer of core's own; a choice of
method per exercise or by an administrator; starter code subtracted as JPlag's base code.
