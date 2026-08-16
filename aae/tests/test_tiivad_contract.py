# coding=utf-8
"""The TSL compiler's output, executed by the grader that actually consumes it.

Everything else in this repo checks one side of this seam. `GoldenOutputTest` pins what the compiler
emits, `PythonSyntaxTest` proves it parses, `TestModelTest` pins the model — and **all three are
green while the generated script is unusable**, which is what EZ-1774 was: keys quoted twice, valid
Python, nothing anywhere noticed for nine days.

This is the check that would have caught it by its symptom. It takes the committed
`tsl/src/test/resources/golden/*.py.expected` — the compiler's real output, regenerated whenever the
emitter changes — feeds each one a submission, and asks tiivad what it made of it.

### The golden files are the interface

Deliberately, rather than invoking the Kotlin compiler from pytest. The `.py.expected` files are
produced by `:tsl`'s own test run and reviewed as a diff, so they are the artefact both sides already
agree on. Reaching across module boundaries for them is honest coupling: if they change, this should
re-run against the new output, and it does.

### The version comes from the Dockerfile

`doc/aae/dockerfiles/tiivad` is the source of truth for which tiivad grades on a real executor, so
that is what this parses — and then **asserts the installed version matches it**. Pinning the version
here as well would create a second truth that drifts; asserting equality means bumping the Dockerfile
is picked up automatically, and a CI step that installs the wrong one fails loudly rather than
testing a version nobody runs.

### What this still does not cover

The container. tiivad is imported into the test process rather than run inside `tiivad:tsl-compose`,
so the image's Python version, its numpy pin and its filesystem are not exercised — a submission that
fails only under Python 3.10 would pass here. That is the remaining half of EZ-1775 and it is the
expensive half; this is the half that catches compiler-to-grader drift, and it needs no Docker.
"""
import ast
import importlib.metadata
import io
import json
import os
import pathlib
import re
import shutil
import sys

import pytest

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
GOLDEN = REPO_ROOT / "tsl" / "src" / "test" / "resources" / "golden"
DOCKERFILE = REPO_ROOT / "doc" / "aae" / "dockerfiles" / "tiivad"


def pinned_tiivad_version() -> str:
    """The version a real executor installs, read from the Dockerfile that installs it."""
    match = re.search(r"tiivad==([0-9]+(?:\.[0-9]+)*)", DOCKERFILE.read_text(encoding="utf-8"))
    assert match, (
        f"No `tiivad==<version>` found in {DOCKERFILE}. That file is the source of truth for which "
        f"tiivad grades on an executor; if the way it installs tiivad changed, this parser has to "
        f"change with it rather than be deleted."
    )
    return match.group(1)


def installed_tiivad_version():
    try:
        return importlib.metadata.version("tiivad")
    except importlib.metadata.PackageNotFoundError:
        return None


PINNED = pinned_tiivad_version()
INSTALLED = installed_tiivad_version()

needs_tiivad = pytest.mark.skipif(
    INSTALLED is None,
    reason=(
        f"tiivad is not installed. Install the version the executor uses:\n"
        f"    pip install tiivad=={PINNED}\n"
        f"CI derives it from {DOCKERFILE.relative_to(REPO_ROOT)}."
    ),
)


def test_the_dockerfile_pins_a_tiivad_version():
    # Runs even without tiivad installed: if this file stops naming a version, every test below
    # would skip with a confusing reason instead of failing.
    assert PINNED
    assert PINNED[0].isdigit()


@needs_tiivad
def test_the_installed_tiivad_is_the_one_the_executor_uses():
    """
    The guard that makes "we test what runs in production" true rather than aspirational.

    Without it, a CI step that installed the wrong version — or a stale local venv — would test a
    tiivad nobody grades with, and every assertion below would still pass. Bump
    `doc/aae/dockerfiles/tiivad` and this fails until CI reinstalls, which is the intended
    behaviour: the Dockerfile leads.
    """
    assert INSTALLED == PINNED, (
        f"tiivad {INSTALLED} is installed but {DOCKERFILE.relative_to(REPO_ROOT)} pins {PINNED}.\n"
        f"Reinstall with: pip install tiivad=={PINNED}"
    )


# --- running a generated script ------------------------------------------------------------------

def cases():
    """
    Every golden spec that has a submission, as (name, outcome, generated script, submission).

    Three suffixes, because not every spec is a gradeable exercise:

    - `<name>.pass.py` — must score 100
    - `<name>.fail.py` — must score below 100
    - `<name>.any.py`  — **contract only**: tiivad must run the script without raising, and the grade
      is nobody's business. `escaping.json` is the case: its expected values are the hostile strings
      themselves, so "does a submission satisfy it" is a question about tiivad's phrase-extraction
      regex rather than about this compiler. What that spec *is* good for is being the nastiest
      script tiivad is ever handed, which is exactly what the no-exception check wants.

    All three feed the contract check; only the first two assert a grade.
    """
    found = []
    for expected in sorted(GOLDEN.glob("*.py.expected")):
        name = expected.name[: -len(".py.expected")]
        for outcome in ("pass", "fail", "any"):
            submission = GOLDEN / f"{name}.{outcome}.py"
            if submission.is_file():
                found.append(pytest.param(name, outcome, expected, submission, id=f"{name}-{outcome}"))

    # A source that finds nothing passes and reads exactly like a clean run — the failure this whole
    # programme keeps meeting. Refuse instead.
    assert len(found) >= 12, (
        f"Only {len(found)} golden submissions found under {GOLDEN}. The point of this test is the "
        f"corpus, so an empty scan is a broken test rather than a pass."
    )
    return found


def run_generated_script(script: str, submission: str, tmp_path) -> dict:
    """
    Execute a generated grading script against a submission, and return tiivad's result document.

    Run in `tmp_path` because the script opens `lahendus.py` by relative path — the container does
    the same, from `/student-submission`. `Results` is a class-level singleton, so it is reset
    per run or the second case inherits the first one's tests and points.
    """
    from tiivad.results import Results

    (tmp_path / "lahendus.py").write_text(submission, encoding="utf-8")
    # Some graders still open the legacy name; `grade_submission` writes both, so mirror it.
    (tmp_path / "submission.py").write_text(submission, encoding="utf-8")

    Results.total_points = 0
    Results.passed_points = 0
    Results.tests = []
    Results.pre_evaluate_error = None

    previous_cwd = os.getcwd()
    stdout = io.StringIO()
    real_stdout = sys.stdout
    os.chdir(tmp_path)
    try:
        sys.stdout = stdout
        # The generated script ends with `print(Results(None))`, so executing it produces the result
        # document on stdout — exactly what the executor captures from the container.
        exec(compile(script, "generated_0.py", "exec"), {"__name__": "__main__"})
    finally:
        sys.stdout = real_stdout
        os.chdir(previous_cwd)

    printed = stdout.getvalue().strip()
    assert printed, "the generated script printed nothing; the executor would have no grade to parse"
    return json.loads(printed.splitlines()[-1])


@needs_tiivad
@pytest.mark.parametrize("name,outcome,expected,submission", cases())
def test_tiivad_accepts_every_generated_script(name, outcome, expected, submission, tmp_path):
    """
    **The EZ-1774 guard.** No test in any generated script may raise inside tiivad.

    An `exception_message` means the *script* was malformed — a key tiivad cannot find, an enum value
    it does not know, an argument that drifted — as opposed to a submission that simply failed a
    check, which is a `status: FAIL` with the teacher's message and is entirely normal. The
    distinction is the whole point: this test is indifferent to whether the submission is any good.

    When EZ-1774 was live, every check dictionary raised `KeyError` here.
    """
    result = run_generated_script(expected.read_text(encoding="utf-8"),
                                  submission.read_text(encoding="utf-8"), tmp_path)

    assert result["pre_evaluate_error"] is None, result["pre_evaluate_error"]

    broken = [(t.get("title"), t.get("exception_message", "").strip().splitlines()[-1:])
              for t in result["tests"] if t.get("exception_message")]
    assert not broken, (
        f"tiivad could not run the generated script for '{name}'.\n"
        f"This is compiler-to-grader drift, not a failing submission: {broken}"
    )


@needs_tiivad
@pytest.mark.parametrize("name,outcome,expected,submission",
                         [c for c in cases() if c.values[1] == "pass"])
def test_a_correct_submission_scores_full_marks(name, outcome, expected, submission, tmp_path):
    result = run_generated_script(expected.read_text(encoding="utf-8"),
                                  submission.read_text(encoding="utf-8"), tmp_path)

    assert result["points"] == 100, (
        f"'{name}' should have scored 100 for a correct submission, got {result['points']}.\n"
        f"tests: {[(t.get('title'), t.get('status')) for t in result['tests']]}"
    )


def expected_values_in(script: str) -> list:
    """The first `expected_value` list in a generated script, as Python will build it."""
    tree = ast.parse(script)
    for node in ast.walk(tree):
        if isinstance(node, ast.Dict):
            keys = {ast.literal_eval(k): v for k, v in zip(node.keys, node.values)}
            if "expected_value" in keys:
                return ast.literal_eval(keys["expected_value"])
    raise AssertionError("no expected_value dict in the generated script")


def test_a_teachers_punctuation_arrives_at_the_grader_intact():
    """
    What the teacher typed, against what tiivad is handed. **The escaping contract, stated exactly.**

    `escaping.json` is not a gradeable exercise — its expected values are the hostile strings
    themselves — which makes it the one place to assert the thing golden files cannot: not that the
    output is *stable*, but that it still *means* what the spec said after crossing a JSON document,
    a Python literal and `ast.literal_eval`.

    Runs without tiivad installed, because it is about the compiler's output rather than the grader.

    The two documented departures are asserted as departures, not skipped. They were prose in
    `PythonSyntaxTest`'s docblock until now; here they are a table that fails when either changes.
    """
    script = (GOLDEN / "escaping.py.expected").read_text(encoding="utf-8")
    spec = json.loads((GOLDEN / "escaping.json").read_text(encoding="utf-8"))

    wanted = spec["tests"][0]["genericCheck"]["expectedValue"]
    got = expected_values_in(script)
    assert len(got) == len(wanted), "the compiler dropped or added an expected value"

    by_spec = dict(zip(wanted, got))

    # Survive byte for byte — every way a teacher's punctuation can end a Python literal early.
    for value in ["ends with a quote'", "1 4 7 ''", "''", "ends with a backslash\\",
                  'say "hello" politely', "', __import__('os').system('id'), '",
                  "100% of {tests} passed", "# not a comment",
                  "ends a literal: ''' and continues", ""]:
        assert by_spec[value] == value, f"{value!r} changed on the way to the grader"

    # Backslashes stay Python escapes: two in the spec become one at the grader. This is the format,
    # not a bug — 18 of the 720 corpus exercises store `\n` and rely on it becoming a newline, and
    # "fixing" it was measured as changing what they grade.
    assert by_spec["ends with two\\\\"] == "ends with two\\"

    # And a value that is only whitespace is trimmed away entirely.
    assert by_spec["   "] == ""


@needs_tiivad
@pytest.mark.parametrize("name,outcome,expected,submission",
                         [c for c in cases() if c.values[1] == "fail"])
def test_a_wrong_submission_does_not_score_full_marks(name, outcome, expected, submission, tmp_path):
    """
    The direction that catches a check which is not actually checking.

    A grader that passes everything is indistinguishable from a working one until somebody submits
    nonsense — and a spec whose check silently does nothing (an unticked box, an empty expected
    value) reaches production looking fine. Asserting *below* 100 rather than exactly 0 keeps this
    honest for specs with more than one test.
    """
    result = run_generated_script(expected.read_text(encoding="utf-8"),
                                  submission.read_text(encoding="utf-8"), tmp_path)

    assert result["points"] < 100, (
        f"'{name}' scored full marks for a submission that should not pass — the check is not "
        f"checking anything.\ntests: {[(t.get('title'), t.get('status')) for t in result['tests']]}"
    )
