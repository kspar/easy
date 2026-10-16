# JPlag Integration (Plagiarism Detection)

The similarity check behind `POST /v2/exercises/{exerciseId}/similarity` uses
[JPlag](https://github.com/jplag/jplag), which compares **language tokens** rather than text. It
replaced a Dice bigram coefficient plus a fuzzywuzzy ratio, neither of which understood code:
renaming every variable in a copied solution defeated both. Python, Java and SQL are compared as code;
anything else word by word.

This document describes what was built. The design it originally proposed — writing submissions to a
temp directory — was rejected, and the reason is the most important thing here.

## How it is put together

Everything is in `core/ems/service/code/similarity/`, one concern per file; the endpoint,
`TeacherCheckSimilarity.kt`, sits one level up and uses only the service and the language.

| File | What it is |
| --- | --- |
| `SimilarityLanguage.kt` | The languages, each bound to its frontend, and how an exercise's language is decided. |
| `SimilarityFrontend.kt` | The contract every frontend keeps — see below — and the refusals it may throw. |
| `PythonFrontend.kt`, `BudgetedParserATNSimulator.kt` | Python: JPlag's ANTLR grammar and listener, run from a string, with a budgeted parse. |
| `JavaFrontend.kt` | Java: javac parses in memory, JPlag's own tree scanner makes the tokens. |
| `SqlFrontend.kt` | SQL: a lexer of core's own, since JPlag has none. |
| `TextFrontend.kt` | Anything else: one token type per distinct word. |
| `SimilarityWorkers.kt` | The pool a comparison's work runs on, and every rule about running it in parallel. |
| `PairRanking.kt` | Compares the pairs and keeps the best, through whichever provider measures them. |
| `SimilarityProvider.kt` | What a way of measuring similarity owes the engine: prepare a cohort, score a pair, give a returned pair's details. |
| `JPlagProvider.kt` | The JPlag provider: tokens from the frontends, JPlag's submission set, one tiling per pair. |
| `SimilarityModel.kt` | What the service takes and returns, and the refusals it throws: too many submissions, busy, timed out. |
| `SimilarityService.kt` | The engine, the same for every provider: one comparison at a time, the bounds, identical submissions grouped, the outcome assembled. |

The contract, in `SimilarityFrontend`, is what lets the rest be language-blind: tokens end in
`FILE_END`; at most `max-solution-tokens` *lexical* tokens are tokenised, counted before anything
costlier runs; a refusal on cost is `TOO_LARGE` or `TOO_COMPLEX`, any other failure is that one
submission's `CANNOT_PARSE`; whether the parser had to recover from a syntax error is reported (see
Language selection); and a frontend is safe to call from several threads at once. Each carries its own
shortest match (see Tuning). **Adding a language** is a frontend of its own, an entry in
`SimilarityLanguage`, a line in `forFileName` (or `gradedBy`, for a grader that runs only that language)
that recognises its exercises, and a calibration of its shortest match — which has no harness in the
repository; the tables below say what was measured. The comparison, the limits, the permit and the
response do not change. On the page, `similarityLanguage.ts` maps the new wire name to a file name for
highlighting. A language whose frontend is a JPlag module also needs its dependency (with the same
exclusions as the others in `core/build.gradle.kts`) and, if it reaches package-private API, a shim in
JPlag's package with a Kover target of its own, since shims sit outside `core.*`.

## Solutions never touch a filesystem

JPlag's documented entry point cannot be used. `JPlagOptions.submissionDirectories` is `Set<File>`,
and every read funnels through `de.jplag.util.FileUtils`, which uses `new FileInputStream(file)` and
`new FileReader(file, charset)`. Both are native and take only the path string, so a `File` subclass,
a Jimfs-backed `toPath()`, and a custom `FileSystemProvider` all fail to intercept them. Using JPlag
the documented way means writing student code to disk.

Instead, JPlag's pipeline is left intact and only the *source of tokens* is replaced:

| Piece | What it does |
| --- | --- |
| `PythonFrontend.kt` | Subclasses `PythonParserAdapter` to reach its `protected` grammar wiring, so the real Python grammar and JPlag's own token-mapping listener run from a `CharStreams.fromString`. |
| `de/jplag/antlr/InMemoryTokenCollectorAccess.kt` | `TokenCollector` and `InternalListener` are package-private, so this is declared in JPlag's package. A split package, legal on a plain classpath. |
| `JavaFrontend.kt` | Parses with javac from an in-memory `JavaFileObject`. JPlag's own Java parse (`JavacAdapter.parseFiles`) takes files, and also cannot run on the production JRE; see Language selection. |
| `de/jplag/java/InMemoryJavacAccess.kt` | JPlag's `TokenGeneratingTreeScanner` is package-private, and so is `Parser.add`, which it hands each token to. The same split package, for the Java scanner. |
| `JPlagProvider.PretokenisedLanguage` | A `de.jplag.Language` whose `parse()` returns the already-computed tokens. `Submission.parse()` then succeeds normally, so JPlag assigns the submission states itself. |

The `File` objects the service builds are identifiers, not paths — they are the keys
`PretokenisedLanguage` looks tokens up by, and nothing ever opens them. `SimilarityServiceTest` plants
files at those paths and checks that nothing reads them, and that nothing is written — javac included.

**Two traps worth knowing before changing any of this.** `SubmissionSet`'s constructor parses every
submission, including base code, whatever built them — an earlier version assumed otherwise and
forced `getState()` to `VALID`, which hid a failing parse and turned any exercise with a template
into an HTTP 500. And every token list must end with `SharedTokenType.FILE_END`: it is the only type
excluded from matching, and `GreedyStringTiling` advances with no bounds check, so a list without one
walks off the end of the array. It is also what `getSimilarityDivisor()` subtracts, so omitting it
inflates every score.

## Language selection

From what the exercise version says about its solutions: its grader's container image, where that
only ever runs one language, and otherwise `ExerciseVer.solutionFileName`'s extension — `.py`/`.pyw`
Python, `.java` Java, `.sql` SQL, anything else text. The resolved language is in the response as
`language`, and the page says so when it is `text` — a word-level result is a weaker answer than a
token one and should not read like it — and how to read the scores when it is `sql`.

**The container image comes first because of SQL.** Every new exercise starts as `lahendus.py`, and the
executor writes every submission as `lahendus.py` whatever the exercise calls it (`aae/containers.py`)
— which is why the Silmused template's first line renames it to `lahendus.sql`. So an SQL exercise often
keeps the default name, and resolved by name alone its SQL was parsed as Python: error recovery kept a
few tokens of each query, and the scores were computed over those with nothing on the page to say so.
An exercise graded by `silmused` is SQL whatever its file is called. An SQL exercise that is neither
graded by Silmused nor named `.sql` is still compared as Python; naming its file `.sql` fixes that, and
gives the editor SQL highlighting besides.

**A cohort that is mostly not code in its language is compared as text.** The same default name sends
every teacher-graded essay or short-answer exercise to the Python parser, whose error recovery made each
line of prose one identical token: unrelated essays paired at 90–100%, short answers were `TOO_SMALL`,
and the page said `python`. So each frontend with a parser reports whether recovery took over a
solution - syntax errors on more than half of its lines, as prose has - and when more than half of a
cohort's solutions were taken over or could not be tokenised at all, the cohort is tokenised again as
text and the response says `text`. A sample of 20 decides first, so an essay cohort is not parsed in full
only to be thrown away. One broken solution is not enough, and must not be: it is compared as code, like
any other (see What the API returns); nor is one construct the grammar does not know in every solution,
which any error at all used to count as.

**Java needs javac at runtime**, which is the JDK's `jdk.compiler` module. Production's
`openjdk-25-jre-headless` includes it (Ubuntu packages every module in the JRE's image) but not the
JDK's `lib/ct.sym`, and JPlag's own Java adapter asks javac for `--release=25`, which needs `ct.sym`:
there it failed on every submission — measured on that package — while passing on any JDK, which is all
a developer or CI runs. The in-memory parse asks for nothing a JRE lacks (`JavaFrontendTest` pins the
options). A runtime without javac at all compares Java as text, says so in the response, and logs a
warning at startup.

## Tuning

The shortest run of tokens that counts as a match is the number that decides whether this is useful,
and it is set per language, in each frontend: it is a property of the token — of what one means and
how many a line of code makes — not of an installation. Python's is **6**, Java's **8**, SQL's **12**,
text's **6**. Python, Java and SQL were calibrated the same way, on synthetic cohorts: several
exercises, each with honest solutions written independently, a copy with every name changed and its
layout redone, and — for Java and SQL — a copy with small structural edits as well. A setting is good
when copies score well above every honest pair. Text's 6 is Python's, inherited rather than measured.
The Java and SQL cells are `max_similarity`, the score the page ranks by. The cohorts and the harness
are not in the repository, so re-measuring means rebuilding them; `SimilarityServiceTest` pins the four
values so that none moves by accident.

**Python.** JPlag's default is 12, tuned for programs much larger than Lahendus sets. Measured on three
exercise sizes, each with one known copy (every identifier renamed) and independently written correct
solutions:

| shortest match | ~20-line exercise: copy / honest max | 3-line exercise: worst honest pair | ~9-line exercise: copy |
| --- | --- | --- | --- |
| 4 | 100% / 58% | **100%** | 100% |
| 6 *(shipped)* | 100% / 58% | **100%** | 100% |
| 8 | 100% / 58% | **100%** | 100% |
| 10 | 100% / 58% | dropped as `TOO_SMALL` | **0%** |
| 12 *(JPlag default)* | 100% / 58% | dropped as `TOO_SMALL` | dropped as `TOO_SMALL` |

Three things follow. **The rename case holds at 100% at every setting** on a realistic exercise,
which is the property the Dice/fuzzywuzzy scorer did not have at all. **The setting does not
separate copying from honest refactoring** — rewriting the same algorithm with `dict.get` sits at
58% regardless, so 6 buys nothing over 12 there. And **there is no value that fixes a trivial
exercise without breaking a short real one**: at 10 the three-line cohort stops being flagged, but
the nine-line FizzBuzz copy stops being found. 6 is the right end of that trade-off, because a
false negative on a real exercise is worse than noise on an exercise too small to plagiarise
meaningfully.

**Java.** Four exercises (two numbers summed, FizzBuzz, array statistics, a small bank-account class),
three or four honest solutions each. The case that decides it is the class of small methods copied in
another order: each method is a run of a few tokens, and the runs stop matching above 8.

| shortest match | bank account: renamed copy / edited copy / honest max | array stats: edited copy / honest max | FizzBuzz: honest max |
| --- | --- | --- | --- |
| 6 | 97% / 76% / 49% | 68% / 45% | 38% |
| 8 *(shipped)* | 81% / 59% / 32% | 68% / 38% | 0% |
| 9 *(JPlag default)* | 59% / 59% / 32% | 68% / 38% | 0% |
| 10 | 59% / **35%** / 32% | 68% / 38% | 0% |
| 12 | 32% / 35% / 32% | 68% / 0% | 0% |

At 10 the edited copy (35%) is no longer told from an honest pair (32%). Below 8 the copies score
higher, but so do honest solutions — FizzBuzz's go from 0% to 38% at 6 — so the margin between them
does not grow.

**SQL.** Six exercises of the kinds Silmused checks — a filtered query, a join with grouping, a
`CREATE TABLE` with constraints, a view, a PL/pgSQL function, a trigger — four or five honest solutions
each, and each student's six answers together as a lab. A lexical token is finer than one of JPlag's
(`FROM a JOIN b ON a.x = b.y` is twelve), so the run is longer:

| shortest match | whole lab: copy / edited copy / honest max / honest median | one query: honest max | trigger: edited copy / honest max |
| --- | --- | --- | --- |
| 6 | 100% / 86% / 60% / 44% | 57% | 87% / 92% |
| 8 | 100% / 80% / 52% / 35% | 57% | 87% / 92% |
| 10 | 100% / 74% / 46% / 23% | 42% | 87% / 92% |
| 12 *(shipped)* | 100% / 67% / 38% / 23% | 0% | 66% / 75% |
| 15 | 100% / 59% / 25% / 14% | 0% | 66% / 51% |

A copy with its names changed and its layout redone scores 100% at every setting, because neither is a
token. Above 12 the shortest answers start being too short to compare at all. The sweep predates the
lexer's treating PostgreSQL's other spellings as one token — `INT` and `INTEGER`, `CHARACTER VARYING`
and `VARCHAR`, `INNER JOIN` and `JOIN`, `~~` and `LIKE`, a function's name used as an alias — which
each let a copy respell its way out of a run. Those can only make two solutions more alike: a respelled
copy now scores as the plain one does, and an honest pair that differed only in such spellings rises
with it. The trigger column is
the caveat to read SQL scores by: **short, formulaic SQL looks alike however it was written** — every
correct trigger of that exercise shares most of its tokens — and no setting separates a copy there.

**On a trivial exercise every pair scores 80-100% and that is not a bug** — four independently
written "read two numbers and print the sum" solutions genuinely share one token stream. Base code
would fix it, not the threshold: passing the solution as base code drops the same cohort to 0%
across the board. But the endpoint has no starter code course students are given to pass (see Base
code below), so an exercise that short is one where this page has nothing to tell you, and the
ranking should be read that way.

One edge worth knowing: validity and matchability are not quite the same test. JPlag's size check
counts `FILE_END`, so a submission one token short of the shortest match passed it and could never
match - a silent 0%, or three copies reported as an identical group. Core now skips such a submission
as `TOO_SMALL` itself, before JPlag sees it. (The calibration tables below predate that: there the
nine-line copy at 10 is neither skipped nor scored.)

## Bounds

`easy.core.similarity.max-submissions` (500) bounds the heap: the whole corpus and its token streams
are in memory for the duration, and the comparison is not interruptible. The cohort is resolved to
submission ids first and the cap is checked on their count, before any solution is read; a cohort
above it is refused with `TOO_MANY_SUBMISSIONS`, carrying `limit` and `requested` so the UI can
name the number.

**What that bound comes to.** The largest share is not the solutions but JPlag's tiling, which builds a
hash table of every submission's token runs — boxed start indices, about 144 bytes a token at SQL's
shortest match — and keeps all of them for the run. Measured with a full GC at the first and the last
pair: about 2 MB for each SQL solution at the 10,000-token cap, 1.4 MB for text, 0.2 MB for Python
(whose tokens are far fewer), so a cohort of 500 near-cap SQL or text solutions needs about 1 GB of heap
on top of the solutions themselves, which may each be up to 500,000 characters (`StudentSubmit`). The
peak arrives within seconds; such a cohort then runs out of time budget anyway, as roughly 500
CPU-seconds of pairs. Production runs core without `-Xmx`, so its heap is a quarter of the host's
memory, and without `-XX:+ExitOnOutOfMemoryError`: on a host where that quarter is near 1 GB, set
`easy_core_java_opts` rather than find out.

The other three settings bound time, which the heap cap cannot see. Each exists because a specific
input, measured, got past everything else:

| Setting | Default | What it stops |
| --- | --- | --- |
| `max-solution-tokens` | 10000 | A solution too long to tokenise cheaply: above it, `TOO_LARGE`, unparsed. Counted in **lexical tokens** — names, literals and symbols, the same unit for every language — before anything costlier runs, because comments never reach a parser: a character cap let a copy padded with one long comment drop out of the comparison while it ran exactly as before. 10,000 is about 35 KB of ordinary code. |
| `max-repetition` | 1000000 | A solution that would make one *pair* slow: above it, `TOO_REPETITIVE`. The tiling cannot be interrupted once a pair is started, and repetition is what makes a pair expensive — two solutions of differently repeated statements took 19 s for their one pair at 16 KB each and 4–5 minutes at 32 KB, each passing its tests. Measured as Σc² over the counts of each distinct run of as many tokens as the language's shortest match: an ordinary solution scores about its own length, a thousand identical statements about a million. It bounds every pair a solution is in (Cauchy–Schwarz), before any pair starts. |
| `timeout-sec` | 120 | The sum of many things each under its bound. Read before each submission is tokenised and before each pair is compared; past it the run is refused with `SIMILARITY_TIMEOUT`, carrying `budget_sec`. What is already running finishes — no parser and not the tiling polls for interruption — which is why every unit of work has a bound of its own. |

Each frontend has limits of its own, in code, because they are facts about a parser rather than about
an installation; each skips the submission as `TOO_COMPLEX`.

**Python:**

- **Brackets nested deeper than 200 are not parsed.** Nesting costs the parser without costing length:
  5,000 nested brackets took over two minutes inside the token cap. 200 is CPython's own limit, so
  nothing refused by it can run.
- **LL prediction has a work budget.** Parsing tries ANTLR's SLL prediction first and falls back to
  full LL only when SLL fails — on a syntax error, which one line of syntax the 2016-era grammar does
  not know is enough to cause. Under LL, `x = ` followed by 500 minus signs took 18 s and 1,000 over
  two minutes, and that is valid Python up to 5,966 of them; SLL parses them in milliseconds, and
  ANTLR guarantees a successful SLL parse is the tree LL would build. The fallback stops at
  1,000,000 + 500 × tokens prediction steps, which ordinary code, broken or not, stays well inside —
  or at 2 s of wall clock, whichever comes first. The clock is needed as well as the count, because a
  step costs more the deeper the nesting: at CPython's limit the count was reached only after 67 s,
  with 1.3 GB on the heap.
- **So does the walk after it.** JPlag's listener decides some tokens by `getText()`, which ANTLR builds
  by concatenating children level by level, so each call costs a node's text times the depth under it.
  199 nested calls around one 120 KB string passed every cap, parsed in a fifth of a second and walked
  for 26 s. The walk has a 2 s clock of its own, read before each node.
- **One parse cannot grow the prediction cache without bound.** The parser's DFA is shared by every
  parse, as ANTLR intends, and prediction on deeply mixed brackets adds a state for each distinct stack
  of rules — one such submission, valid and under every other limit, added about 120 MB that stayed
  after it was refused, for the life of the process. A parse may add at most 5,000 states (the most any
  ordinary solution added was 619, on an empty cache), and a cache past 30,000 is replaced by an empty
  one (`PythonParserCache`); 500 solutions came to 2,057 between them.
- **Form feeds are read as CPython reads them.** The grammar ends a line at one; CPython reads one inside
  a line as a space, and one in the indentation as where the indentation starts. A verbatim copy with a
  few inserted scored 20% before they were normalised.

**Java:**

- **The token cap bounds javac's parse**, which is linear on nearly everything and quadratic on a few
  shapes: 50,000 chained `<` took 21 s. Within the cap the worst measured is a few hundred milliseconds.
  The parse is the one step nothing can stop once started, so the count reads the source as javac does
  wherever a difference could hide code: unicode escapes are translated first, a line ends at a CR as
  well as an LF, a char literal holds one character, and a text block opens only at a line's end.
  Counted naively, one escaped newline in a `//` comment hid 10,000 chained comparisons from the cap,
  and 160 KB of them then took javac 42 s. A source with a unicode-escaped backslash, `\u005c`, is counted with
  its comments and literals as code rather than by javac's subtler rules for it.
- **The tree walk has a 2 s clock.** JPlag's scanner asks javac where every node starts and ends, and
  javac walks the node's subtree to answer, so a chain or nesting n deep costs n²: 5,000 chained calls
  took 5 s and 50,000 ten minutes, 20,000 nested `if`s 30 s. The clock sits in the position lookups,
  which is where nearly all of that time goes; the exception is a chain of field accesses, whose cost
  is JPlag's own `toString()` on each and which took 2.2 s under the cap, unclocked.
- **Syntax errors are recovered from**, as for Python, where JPlag's own Java module refuses any file
  with one. A broken line is not a way out of the comparison — with one exception, javac's own: about
  fifty errors at one position make it give up rather than loop, and that file is `CANNOT_PARSE`, named
  on the page.
- **A position before the start of the file is read as its first character.** JPlag's scanner computes
  one for an enum whose constants have bodies, near the start of a file, and javac's line map throws on
  it; code that compiled was dropped, as it is by JPlag's own Java module.

**SQL** has no limit of its own: its lexer is one linear pass with nothing to nest, and it cannot fail —
what it does not recognise it skips. It follows PostgreSQL's lexical rules rather than Unicode's
wherever the two differ, because each difference is somewhere a copy can hide. PostgreSQL reads bytes
and takes every byte of a character past ASCII for a letter, so a zero-width space, an emoji or `€` is
part of a name to it and `$€$` a dollar-quote delimiter; its line comment ends at a CR as well as an LF;
a function body written as a string, `AS '…'`, is lexed as code like a dollar-quoted one. And inside any
such body the closing delimiter ends every string, comment and name, as it does for PostgreSQL — which
is also a bound: otherwise a valid `SELECT $$It's$$;`, or `SELECT $€$It's$€$;` read with the wrong
letters, opens a string that never closes and hides everything after it. Where a body closes is found
from an index of the delimiters made in one pass, not by searching the rest of the source from each
opener: 40,000 distinct openers that never close took 4 s that way.

The pool's threads have a 64 MB stack. On the JVM's default of 1 MB the parsers overflowed on code
that runs — Python's on `not` chained 5,967 times or `lambda:` 2,983 times, javac's on 5,000 nested
parentheses or a 5,000-term sum — and an overflow was a `CANNOT_PARSE` skip, so one such line took a
copy out of the comparison while it still passed its tests. The Python bracket limit is what makes the
larger stack safe there: a nesting that used to overflow within a second would otherwise run on for
minutes.

## Running in parallel

Tokenising and comparing both run on a pool of platform threads made for the one comparison and closed
with it (`SimilarityWorkers`), half the cores in size — this runs inside the web process that serves
every other request, and a pool sized to the machine measured 746% CPU on eight cores for over a
minute. Three rules, each of which the code this replaced broke, found by two independent reviews:

- **One failure stops the rest.** The first task to throw raises a stop flag; a task not yet started
  then returns without running, and a long one — a worker's share of the pairs — reads it between
  pairs. Before, an OutOfMemoryError while tokenising one submission left the other threads working
  through the rest of the cohort under the same memory pressure.
- **Nothing outlives the run.** Every task has finished before the call returns or throws, and the pool
  waits for its threads when it closes, so the permit passes to the next comparison only once this
  one's work is over. It used to be `shutdownNow()`, which interrupts and does not wait — and nothing
  here polls for interruption, so a failed run's last tasks ran on beside the next run.
- **The worst failure is reported.** An Error over any other exception, any other over a timeout; the
  rest are attached as suppressed. Read in list order, a timeout in one task hid an OutOfMemoryError in
  another, and the endpoint answered with a named refusal nobody is told about over a dead heap.

The workers share nothing but that flag: each returns its own best pairs and its own counts, and the
merge is by a total order, so the answer is the same on any number of threads (`SimilarityServiceTest`
runs one cohort on one to sixteen). What is shared across threads by design — ANTLR's DFA caches, JPlag's
`GreedyStringTiling` — was reviewed for it: ANTLR publishes DFA states under its own locks, and the
tiling's caches are synchronized, with base-code markings written before any worker starts.

The pool is not quite the only place work runs in parallel: JPlag's `SubmissionSet` parses its
submissions on virtual threads of its own, inside its constructor. The work there is handing over tokens
the pool already made, and the rules above do not reach it; what does go wrong there leaves a submission
unparsed, which the service refuses to report as skipped (`skipReason`).

**Why not coroutines.** The application uses them for what they are good at, waiting — on the executor,
on long polls. This work never waits: it is CPU-bound, cannot be cancelled part way through a unit by
anything cooperative, since neither ANTLR, javac nor the tiling checks, and needs a stack deeper than any
shared dispatcher's. A coroutine version would need this same pool underneath, `runBlocking` on the
request thread in front, and would add a silent failure of its own: an `async` launched without the
pool's dispatcher runs on runBlocking's event loop — the whole cohort in sequence, on the request thread's
shallow stack — and still passes every test. Structured concurrency is what coroutines would have
brought, and the three rules above are exactly that, written once and tested on their own
(`SimilarityWorkersTest`). JDK 25's `StructuredTaskScope` is still a preview API, and virtual threads
cannot be given a stack size.

## Cost

On 500 generated beginner-style Python solutions (1.7 MB, median 2.7 KB, eight cores):

| | Before | Now |
| --- | --- | --- |
| Tokenising, per thread | 17–24 ms/KB | 2.5–3 ms/KB |
| Comparing 124,750 pairs | 6–9 s, all cores | 1.5–2.1 s, four cores |
| Whole run | 18–28 s | 5.5 s on a cold JVM, 1.7–2.2 s warm |
| Worst pair `max-repetition` admits | 19 s at 16 KB each, minutes at 32 KB | 0.8 s |

Java costs about 12 ms per solution whatever its size — javac's setup is nearly all of it — so 500
take some 1.5 s on four threads. Parsing many solutions in one javac task measured 0.2–0.4 ms each; it
is not done, because one task is one parse nothing can interrupt, and a batch would make one
pathological solution everybody's problem. SQL's lexer is linear and negligible.

Tokenising Python was slow because of one predicate: the grammar's NEWLINE rule starts with
`{atStartOfInput()}?`, which stops ANTLR caching the lexer's start state, so every token rebuilt it.
`StartStateCachingLexerATNSimulator` caches it past the start of input, where the predicate is always
false, and produces the stock lexer's tokens exactly; `PythonFrontendTest` holds it to that. It caches
only a start state whose closure met no other predicate, so a grammar that gains one falls back to
ANTLR's own behaviour rather than to a cached state that is wrong for some inputs. The pairs are
compared by `PairRanking` over JPlag's `GreedyStringTiling` rather than by JPlag's driver, which
submitted every pair up front as a virtual thread on every core, kept every comparison however low,
and sorted them all by a score it recomputed at each step of the sort. The loop is also what reads the
deadline between pairs, keeps only the top 100, and leaves a student's own pairs uncompared instead of
comparing and discarding them.

## One comparison at a time

One comparison runs at a time, and the submissions are loaded *inside* that permit rather than before
it — otherwise every queuing caller would hold a full cohort at once, which is the opposite of what
one permit is for. One caller may wait for it, for up to 30 s, and any further caller is refused at
once with `SIMILARITY_BUSY`: every waiter parks a Tomcat thread, and with no bound some 200 parallel
requests on one teacher's token parked all of Tomcat's. A waiter interrupted by a server shutting down
is refused the same way, rather than reaching the error handler as a 500. That is a global lock, not a
per-cohort one, so "try a smaller group" is the wrong advice for it and the page says something else.

One side effect worth knowing about: the service replaces JPlag's progress-bar provider with a no-op
at construction. JPlag's default logs one INFO line per step, and there is no logger-level fix, because
those lines share a category with everything else it says. Its comparison driver stepped once per pair
— about 125,000 lines and 12 MB per click at the ceiling — and although `PairRanking` now does the
comparing, `SubmissionSet` and `TokenSequenceMapper` still step once per submission. It is a
process-wide static, set once.

## Base code

The service can subtract starter code as JPlag base code, so code every student was given is not
reported as similarity — without it a fill-in-the-blank exercise flags its entire cohort. The
endpoint passes none. The only template column, `anonymous_autoassess_template`, is the embed
page's starting code for anonymous visitors: course students never see it, so subtracting it changed
every course cohort's scores over an embed-only setting, while an exercise that gives its starter
code in the text got no subtraction at all. A starter-code field course students are actually given
is what would belong here.

When base code is passed it is tokenised on the pool like a submission, and held to the same bounds —
size, nesting and repetition, since it is tiled against every submission. One shorter than the
language's shortest match, or refused on a bound, is dropped with a log line instead of being used:
`SubmissionSet` refuses a short base code by throwing out of its constructor, and
`# write your solution here` is one token.

## What the API returns

`scores[]` carries `similarity` and `max_similarity`, both 0–100, plus `matches[]` as line ranges in
each submission. The two scores answer different questions. `similarity` is JPlag's average: matched
tokens on both sides over both lengths, symmetric, and high only when the two are largely the same.
`max_similarity` is the matched share of whichever submission is covered more — in practice the
shorter one — so a short solution pasted whole into a long one scores near 100% there and low on the
average; ranking on the average alone would bury exactly that pair. Both high: the two are much the
same. Only the maximum high: one is contained in the other. For one release `score_a` and `score_b`
repeat the two under the names the pre-JPlag page reads, so a tab still on that bundle keeps working
until it reloads.

**The pairs are ranked by how much code they share** — matched tokens — and only then by
`max_similarity`, then the average. By the percentage alone the size of what is shared did not count: a
twenty-token stub, the starter code handed in unchanged, is 100% of itself inside every solution that
grew from it, and two such stubs are 100% on both scores. With twelve of them among twenty students a
real copy fell from first to 67th, which on a larger class is off the returned top 100 altogether; ranked
by what is shared it was first again. The contained copy the maximum exists for keeps its place, since a
real solution pasted whole shares all of itself. The page's threshold still filters on `max_similarity`,
so the cut is not by what is shared alone: that dropped a short copy at 95% sharing few tokens below a
hundred long honest pairs at 45%, and a teacher filtering on 90% was shown nothing. The response is the
top 100 by what is shared and the top 100 by `max_similarity` together — at most 200 pairs, in the
first order — so whatever lies above a threshold is what the percentage alone would have kept there,
as long as fewer than 100 pairs do.

**Identical submissions by three or more authors are one group** (`groups[]`, each its members' ids):
the same text but for layout, and so the same tokens. Not the tokens alone: for Python and Java those
leave out operators, literals and names, so a maximum, a minimum and a sum over a list were one group. A group
is compared once, so `scores[]` holds no pair between its members and at most one between the group and
any other submission, naming one member for it — the lowest id whose author differs from the other side's,
so that one student's own attempts are still never paired. Every member is in `submissions`, for the
page to name, but only the first carries its `solution`, and any member a pair names: the rest are that
code but for layout, and a class that mostly handed in one file sent it once per student. The page
lists each group once, with its students and the first one's code, and marks a pair side that stands
for a group with how many more students it is. Twelve untouched starters were 66 pairs at 100% among themselves; a ring of fifteen identical copies
was 105. Two identical submissions stay a pair, shown side by side, and one student's identical attempts
are no group.

The page highlights the matched ranges directly; deriving them from a character diff instead would
contradict the score, worst on exactly the renamed-copy case the change exists for. `language` is
`python`, `java`, `sql` or `text`. `skipped[]` lists what could not be compared — `CANNOT_PARSE`,
`TOO_SMALL`, and the three refusals above, `TOO_LARGE`, `TOO_COMPLEX` and `TOO_REPETITIVE` — so "no
similar pairs" cannot quietly mean "half the cohort was dropped". The page names each skipped student
under their reason, with a link: counts were enough while every skip was an accident, and a solution
that runs but could not be compared is itself worth a look. Recovered syntax errors are **not** a
reason to skip, in Python or Java: the parser recovers and the rest is compared, and excluding them
would let a plagiarist opt out by appending one line the grammar dislikes. Wrapping a Python copy in
syntax the grammar does not know — `match`, a walrus, `type` — was measured and still scores 100%.

## Not built

Cluster detection beyond identical groups, and JPlag's HTML report viewer. The response is the pairs, the
groups of identical submissions, the submissions they name, how many were compared, what was skipped, the
language and a truncation flag; clustering similar but not identical submissions would need UI that does
not exist.
