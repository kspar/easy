# The OK_V3 result format

The JSON document a grading library prints when it has assessed a submission. This file is the
specification; [`ok-v3.schema.json`](ok-v3.schema.json) beside it is the part a machine can check,
and [`ok-v3-examples/`](ok-v3-examples/) holds real output from the graders that produce it.

It used to live in the description of EZ-1509 (Design executor <--> core communication protocol).
That issue is now history; this file is the contract, and `git log` on it is the changelog. Link to
it at a commit when telling a grader's maintainer what changed.

## Who produces it, who reads it

Producers, each in its own repository with its own maintainers:

- **tiivad** — grades Python exercises written in TSL. Every TSL exercise on the platform.
- **silmused** — grades SQL exercises.

`pygrader` and `imgrec` still emit the legacy plain-text format described at the end.

Consumers, all in this repository:

| | reads | file |
| --- | --- | --- |
| executor (aae) | `result_type`, `points` | `aae/server.py`, `parse_v3` |
| core | `tests[].status` and three fields it drops from passing tests before the AI tutor sees the rest | `core/src/main/kotlin/core/ems/service/ai/AiFeedbackPrompt.kt` |
| web app | everything, to render the student's result | `web/src/features/course-exercise/okV3.ts`, `AutoTestResults.tsx` |
| AI tutor | the whole document, verbatim, with a prose description of the fields in its system prompt | `AiFeedbackPrompt.kt` |

Core stores the document verbatim as the assessment's feedback and serves it to the web app
unchanged. Nothing between the container and the browser rewrites it.

## How it travels

The grading script runs in a container. **Everything the container writes, stdout and stderr
together, must be exactly one JSON document** and nothing else: the executor reads the container's
combined log and hands the whole of it to `json.loads`. A warning printed to stderr by a library, a
stray `print` left in a grading script, a progress line — any of these turn a valid result into "not
JSON", and what happens next is not what a grader author expects:

1. The output is tried as OK_V3: valid JSON with `"result_type": "OK_V3"`. `points` is read with
   `int()`; the raw text becomes the feedback.
2. Otherwise it is tried as the legacy format: the last line is `grade: <n>` and the feedback is
   everything before a line of fifty `#`.
3. Otherwise the student gets 0 points and an Estonian sentence saying an unexpected error occurred,
   followed by the raw output. EZ-1794 is about that sentence blaming the student for what is
   usually an infrastructure fault.

A container that exceeds its time or memory limit never gets this far: the executor replaces
whatever it printed with its own message and 0 points, so those two verdicts are plain text, not
OK_V3.

There is no envelope. EZ-1509 described a `message_type` wrapper with `OK_V3`, `OK_LEGACY` and
`ERROR_V3` variants around this document. It was never built; the document is printed bare.

## The document

```jsonc
{
  // The format's version. The executor keys on exactly this string.
  "result_type": "OK_V3",

  // Which library produced this, with its version. Free-form; for a human reading a stored result.
  "producer": "tiivad 0.0.33",

  // When testing finished. UTC, ISO 8601, with the Z. Optional.
  "finished_at": "2026-09-25T14:56:18Z",

  // The grade. An integer from 0 to 100, and the only number the executor reads.
  "points": 100,

  // Non-null when the submission could not be tested at all through the student's own fault:
  // a syntax error, a required file missing. Shown to the student as is, in full.
  // When non-null, `tests` is empty.
  "pre_evaluate_error": null,

  // Every test of the exercise, in display order. A test that did not run is present with
  // status SKIP rather than absent, so the student sees what they did not reach.
  "tests": [
    {
      // The test's name as the student sees it. May be empty; must be present.
      "title": "Programm tervitab kasutajat",

      // PASS, FAIL or SKIP.
      "status": "PASS",

      // The lines fed to the program on standard input, in order.
      "user_inputs": ["Mari", "18"],

      // Text files the test placed in the working directory before the program ran.
      // Shown when non-empty, so the student can see what their program was reading.
      "created_files": [{"name": "andmed.txt", "content": "rida 1\nrida 2\n"}],

      // Everything the program wrote to standard output, as one string. Null when the program
      // did not run or there is nothing worth showing.
      "actual_output": "Mari\n18\nTere, Mari!\n",

      // When the test ran a modified copy of the submission, that copy. Null otherwise.
      "converted_submission": null,

      // A traceback or error message when the test itself could not complete. Shown to the
      // student in full. Null when nothing went wrong. A student's program raising is normally
      // a failed check with a message, not this.
      "exception_message": null,

      // The checks that make up the test, in order. May be empty.
      "checks": [
        {
          // What was checked, as the student sees it. May be empty when the feedback says it all.
          "title": "Kontrollin väljundit",
          // PASS, FAIL or SKIP.
          "status": "PASS",
          // Why. Should be non-empty for a failed check; may be empty or absent for a passed one.
          "feedback": "Väljund on õige"
        }
      ]
    }
  ]
}
```

Three rules that the annotations do not spell out:

- **Unknown fields are ignored.** Every consumer reads the fields it knows and skips the rest, so a
  producer may add fields and an addition to this format is never a breaking change. tiivad sends
  `actual_file_output` on every test; nothing reads it, and nothing minds.
- **A check with neither a title nor feedback is not shown.** Everything else is: a check with only
  a title renders as the title, one with only feedback renders as the feedback, one with both
  renders as a heading and a line under it. Passing checks are shown too, so a student can see what
  went right (EZ-1834).
- **Strings are shown as text, not markup.** Newlines are kept; HTML is not interpreted.

## What consumers tolerate

The web app and the executor accept more than the schema allows, because real producers sent it
before the format was pinned down. Nothing new should rely on any of this:

- `points` as a numeric string (`"75"`). The executor calls `int()` on it.
- A check with `title` null or absent, or `feedback` absent. Treated as empty.
- A test with `created_files` absent. Treated as empty.

## Checking a grader's output

```sh
pip install jsonschema
some-grader < submission | bin/okv3-check          # from stdin
bin/okv3-check result.json                         # or a file
```

Exit code 0 means the document is valid OK_V3; 1 lists what is wrong. The same schema runs in CI:
`aae/tests/test_ok_v3_schema.py` validates the examples, the sample assessments in core's test data,
and, when tiivad is installed, tiivad's real output for every golden TSL exercise.

## Changing the format

- **Adding an optional field:** add it to the schema and to the annotated document above, note it
  under History, and tell the producers' maintainers with a link to this file at the commit. No
  consumer needs to change until it wants to use the field.
- **Changing what an existing field means, or removing one:** that is `OK_V4`. Bump `result_type`,
  have the executor and web app accept both for as long as any stored assessment or any deployed
  grader still speaks the old one, and only then retire it. Stored assessments are never rewritten,
  so the web app keeps rendering `OK_V3` for as long as a student can open an old submission.

## The legacy format

What `pygrader` and `imgrec` still print, and what the executor falls back to when the output is not
OK_V3. Do not use it for anything new.

```
<feedback, plain text, any number of lines>
##################################################
grade: 75
```

The grade line is the last line; the separator is fifty `#`. Case and surrounding whitespace on the
grade line do not matter.

## History

- **2022-10** — designed in EZ-1509. The `message_type` envelope described there was never
  implemented.
- **2023-08** — EZ-1555: tiivad's first output had `user_input` for `user_inputs` and `false` where
  the nullable strings should have been null. Fixed in tiivad; the field names here are the ones
  that stuck.
- **2023-11** — EZ-1562: `finished_at` added, optional.
- **2023-09** — EZ-1565: the Thonny plugin renders it too. Its parser lives in that plugin's
  repository.
- **2026-08** — EZ-1834: check titles are rendered, and passing checks are shown. No change to the
  format; a change to what the web app made of it.
- **2026-09** — the specification moved from EZ-1509 to this file, with a schema and a validator.
