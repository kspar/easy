# coding=utf-8
"""Turning a grader container's stdout into a grade.

This is the last thing between a container's output and a number on a student's screen, and it is
pure text handling with no schema — two formats, a sentinel line, a separator, and several ways to
be malformed. Getting it wrong does not raise anywhere useful: `post_grade` catches every exception
from here and turns it into 0 with an apologetic message, so a parser that is too strict silently
zeroes correct submissions and one that is too lax reads a grade out of a student's own output.

Both formats are covered because both are live: `OK_V3` JSON is what tiivad emits, and the legacy
`grade:` line is what pygrader and imgrec still emit (see the TODO in `server.py`).
"""
import json

import pytest

from server import parse_assessment_output, parse_v3

SEP = "#" * 50


# --- the v3 JSON format ----------------------------------------------------------------------

def test_v3_output_is_parsed_and_the_whole_json_is_the_feedback():
    raw = json.dumps({"result_type": "OK_V3", "points": 75, "tests": [{"title": "t"}]})

    points, feedback = parse_assessment_output(raw)

    assert points == 75
    # The feedback is the raw JSON, not a rendering of it: core stores it and the web app is what
    # knows how to display a v3 result. Summarising it here would lose the per-test detail.
    assert feedback == raw


def test_v3_points_given_as_a_string_still_parse():
    # `int(j['points'])` is deliberate — a grader that emits "75" rather than 75 is not wrong
    # enough to zero a student over.
    assert parse_assessment_output(json.dumps({"result_type": "OK_V3", "points": "75"}))[0] == 75


def test_a_result_type_that_is_not_v3_falls_through_to_the_legacy_parser():
    # Not an error: the fallthrough is how legacy graders keep working. Returning None here is what
    # makes `parse_assessment_output` try the other format.
    assert parse_v3(json.dumps({"result_type": "OK_LEGACY", "points": 10})) is None


def test_output_that_is_not_json_falls_through_rather_than_raising():
    assert parse_v3("grade: 50") is None


# --- the legacy format -----------------------------------------------------------------------

def test_legacy_output_takes_the_grade_from_the_last_line_and_the_feedback_from_before_the_separator():
    raw = f"Test 1 passed\nTest 2 failed\n{SEP}\ngrade: 50"

    points, feedback = parse_assessment_output(raw)

    assert points == 50
    assert feedback == "Test 1 passed\nTest 2 failed\n"


def test_the_grade_line_is_case_insensitive_and_may_be_padded():
    assert parse_assessment_output(f"out\n{SEP}\n  GRADE:  100  ")[0] == 100


def test_trailing_whitespace_after_the_grade_line_does_not_hide_it():
    # `.rstrip()` before splitting is what makes this work; a container's output usually ends in a
    # newline, so without it the last line is empty and every submission fails to parse.
    assert parse_assessment_output(f"out\n{SEP}\ngrade: 42\n\n  \n")[0] == 42


def test_only_the_last_separator_splits_feedback_from_the_grade():
    # A grader whose own feedback contains the separator — a test that prints a rule, say — must not
    # have its feedback truncated at the first one.
    raw = f"part one\n{SEP}\npart two\n{SEP}\ngrade: 10"

    points, feedback = parse_assessment_output(raw)

    assert points == 10
    assert feedback == f"part one\n{SEP}\npart two\n"


@pytest.mark.parametrize("raw", [
    "no grade line at all",
    f"out\n{SEP}\nthe grade is 50",
    f"out\n{SEP}\ngrade: 50: 60",
    f"out\n{SEP}\ngrade: fifty",
    f"out\n{SEP}\ngrade: 50.5",
    f"out\n{SEP}\ngrade: -10",
    "grade: 50",
    "",
])
def test_malformed_output_raises_rather_than_guessing(raw):
    """
    Every one of these is a grader that is broken, and the honest answer is to say so.

    `post_grade` turns the exception into 0 plus `SOMETHING_FAILED_MESSAGE` **with the raw output
    appended**, so a teacher can see what the container actually printed. Guessing a number here
    would replace that with a plausible grade and no way to tell it was invented.

    Note `grade: 50` with no separator is in the list: the separator is what marks where feedback
    ends, and without it there is no way to know how much of the output is feedback.
    """
    with pytest.raises(Exception):
        parse_assessment_output(raw)


def test_a_students_own_output_cannot_be_read_as_the_grade():
    """
    The one that would be a scandal.

    A submission that prints its own `grade: 100` line, and whose grader then prints a real one,
    must be graded by the grader. The parser reads the **last** line, which is the grader's, and the
    student's line ends up in feedback where it belongs.
    """
    raw = f"student printed: grade: 100\n{SEP}\ngrade: 0"

    points, feedback = parse_assessment_output(raw)

    assert points == 0
    assert "grade: 100" in feedback
