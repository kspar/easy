# coding=utf-8
"""The OK_V3 result format, held to its own specification.

`doc/aae/feedback-format.md` says what a grading library must print and `doc/aae/ok-v3.schema.json`
says it in a form a machine can check. A specification that nothing checks drifts the way EZ-1509's
did: the envelope it described was never built, the field names it gave were wrong for tiivad's
first release (EZ-1555), and by 2026 nobody could say whether it was current.

So this holds the schema against every OK_V3 document the repository knows of:

- the examples committed beside the specification, which are real grader output;
- the sample assessments in core's test data, which the web app's tests and screenshots render;
- tiivad's actual output for every golden TSL exercise, when tiivad is installed — the one source
  that is not written by hand.

And, because a validator that accepts everything reads exactly like a green run, it holds the
schema against documents it must refuse: the two mistakes from EZ-1555, and the shapes the format's
rules forbid.
"""
import json
import pathlib
import re

import jsonschema
import pytest

from test_tiivad_contract import cases, needs_tiivad, run_generated_script

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA_FILE = REPO_ROOT / "doc" / "aae" / "ok-v3.schema.json"
EXAMPLES = REPO_ROOT / "doc" / "aae" / "ok-v3-examples"
TESTDATA = REPO_ROOT / "core" / "src" / "main" / "resources" / "db" / "changesets" / "testdata.xml"

SCHEMA = json.loads(SCHEMA_FILE.read_text(encoding="utf-8"))
VALIDATOR = jsonschema.Draft202012Validator(SCHEMA)


def errors(document) -> list:
    """Every way `document` breaks the schema, as one line each, so a failure says what is wrong."""
    return [f"{'/'.join(str(p) for p in e.absolute_path) or '<root>'}: {e.message}"
            for e in VALIDATOR.iter_errors(document)]


def test_the_schema_is_itself_valid():
    jsonschema.Draft202012Validator.check_schema(SCHEMA)


# --- documents that must pass ----------------------------------------------------------------------

def example_files():
    found = sorted(EXAMPLES.glob("*.json"))
    assert len(found) >= 4, f"expected the committed examples under {EXAMPLES}, found {len(found)}"
    return [pytest.param(f, id=f.stem) for f in found]


@pytest.mark.parametrize("example", example_files())
def test_every_committed_example_is_valid(example):
    assert errors(json.loads(example.read_text(encoding="utf-8"))) == []


def sample_assessments_in_testdata():
    """
    The OK_V3 feedback strings in core's test data, out of their SQL string literals.

    They are single-quoted SQL with `''` for a quote, one per line. A reader that finds none passes
    for the wrong reason, so the count is asserted.
    """
    text = TESTDATA.read_text(encoding="utf-8")
    literals = re.findall(r"'(\{\"producer\":(?:[^']|'')*\})'", text)
    assert len(literals) >= 5, f"found only {len(literals)} OK_V3 documents in {TESTDATA.name}"
    return [pytest.param(json.loads(lit.replace("''", "'")), id=f"testdata-{i}")
            for i, lit in enumerate(literals)]


@pytest.mark.parametrize("document", sample_assessments_in_testdata())
def test_every_sample_assessment_in_core_testdata_is_valid(document):
    assert errors(document) == []


@needs_tiivad
@pytest.mark.parametrize("name,outcome,expected,submission", cases())
def test_tiivads_real_output_is_valid(name, outcome, expected, submission, tmp_path):
    """
    The check that keeps the specification honest: what the grader actually prints, not what a
    person wrote down about it. A tiivad release that renames a field fails here, before the web
    app renders an empty accordion for every student.
    """
    result = run_generated_script(expected.read_text(encoding="utf-8"),
                                  submission.read_text(encoding="utf-8"), tmp_path)
    assert errors(result) == []


# --- documents that must fail ----------------------------------------------------------------------

def minimal():
    return {
        "result_type": "OK_V3",
        "producer": "test",
        "points": 0,
        "pre_evaluate_error": None,
        "tests": [{
            "title": "t",
            "status": "FAIL",
            "user_inputs": [],
            "created_files": [],
            "actual_output": None,
            "converted_submission": None,
            "exception_message": None,
            "checks": [{"title": "c", "status": "FAIL", "feedback": "why"}],
        }],
    }


def test_the_minimal_document_passes():
    # The base the refusals below are built on. If this fails, every refusal is meaningless.
    assert errors(minimal()) == []


def rejected(mutate):
    document = minimal()
    mutate(document)
    return errors(document)


def test_ez_1555_user_input_singular_is_refused():
    def mutate(d):
        d["tests"][0]["user_input"] = d["tests"][0].pop("user_inputs")
    assert any("user_inputs" in e for e in rejected(mutate))


def test_ez_1555_false_for_a_nullable_string_is_refused():
    def mutate(d):
        d["tests"][0]["actual_output"] = False
    assert any("actual_output" in e for e in rejected(mutate))


def test_a_pre_evaluate_error_with_tests_is_refused():
    def mutate(d):
        d["pre_evaluate_error"] = "SyntaxError"
    assert any("tests" in e for e in rejected(mutate))


@pytest.mark.parametrize("points", [-1, 101, 50.5, "75"])
def test_points_outside_an_integer_0_to_100_are_refused(points):
    def mutate(d):
        d["points"] = points
    assert any("points" in e for e in rejected(mutate))


def test_an_unknown_status_is_refused():
    def mutate(d):
        d["tests"][0]["checks"][0]["status"] = "OK"
    assert rejected(mutate)


def test_a_document_of_another_result_type_is_refused():
    def mutate(d):
        d["result_type"] = "OK_LEGACY"
    assert any("result_type" in e for e in rejected(mutate))


def test_a_check_without_a_title_key_is_refused():
    # Empty is fine and common; absent is not. The web app tolerates it, the contract does not.
    def mutate(d):
        del d["tests"][0]["checks"][0]["title"]
    assert any("title" in e for e in rejected(mutate))


def test_an_unknown_field_is_allowed():
    # The rule that makes an additive change non-breaking. tiivad sends `actual_file_output` today.
    def mutate(d):
        d["tests"][0]["actual_file_output"] = "18"
        d["anything_else"] = {"nested": True}
    assert rejected(mutate) == []
