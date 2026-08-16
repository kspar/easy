# Golden specs

One `<name>.json` spec per case, with the Python the compiler produces from it committed beside it
as `<name>.py.expected`. `GoldenOutputTest` compiles each and compares.

```sh
# see what changed
./gradlew :tsl:test --tests '*GoldenOutputTest*'

# bless the new output, then read the diff before committing it
./gradlew :tsl:test --tests '*GoldenOutputTest*' -Ptsl.golden.update=true
git diff tsl/src/test/resources/golden
```

## What these are for

**The diff is the review artefact.** Same argument as `doc/core/api-shapes.json`: a change to the
emitter is invisible in a Kotlin diff and obvious in a diff of its output. EZ-1774 is the
demonstration — `PyDict` learned to quote its own keys, three callers were already quoting theirs,
and every check dictionary in the system got keys like `'\'check_type\''` for nine days. It reached
master, and dev, and would have shipped.

That defect is **valid Python**, so the syntax test cannot see it. It is not a crash, so nothing
downstream reports it. What it is, is a one-character change in every generated script — which is
exactly what a golden file shows and nothing else does.

## Choosing cases

One spec per test type the model supports, because the emitter has a branch per type and an
untouched branch is an unreviewed one. Types are weighted by what teachers actually use — counted
from the 720-exercise migration corpus on 2026-08-16:

```
1681  program_execution_test        127  contains_test
1363  function_execution_test       117  class_instance_test
 365  definition_test                34  function_is_test
 273  calls_test                     20  placeholder_test
```

Plus `escaping.json`, which is not a type at all: it is every way a teacher's punctuation reaches a
Python string literal. That file is the one to look at first when a `PyStr` change is proposed.

## Rules

- **Read the diff before blessing it.** A golden file regenerated without reading it records the bug
  as the expectation and makes the next diff clean. `-Ptsl.golden.update=true` exists to be used
  once per deliberate change, not as a way past a failure.
- **These pin what the compiler does, not what tiivad accepts.** Blessing a wrong expectation makes
  it wrong forever, and nothing here would notice. That gap is EZ-1775.
- Keep them small. A golden file is read by humans in a diff; a 400-line one is not.
