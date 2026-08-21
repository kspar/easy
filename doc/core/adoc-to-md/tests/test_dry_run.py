"""Tests for the adoc -> markdown dry run's classifier.

`math_delimiters_only` decides whether a flagged exercise is filed as "maths, delimiters only" or as
"text differs after round-trip". That is not cosmetic: `build_payload.py --include ok,math` **writes**
the maths bucket and holds the other, so every case here is the difference between an exercise
shipping and an exercise waiting for a human.

Which makes the negative tests the important half. A classifier that answered True more often would
pass every positive case in this file and quietly write exercises whose text really did change.
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from dry_run import math_delimiters_only  # noqa: E402


class TestDelimitersOnly:
    def test_asciidoctor_and_pandoc_delimiters_are_the_same_formula(self):
        assert math_delimiters_only(r"Arvuta \(x\) väärtus.", "Arvuta $x$ väärtus.")

    def test_pandoc_pads_the_delimiters_with_spaces(self):
        assert math_delimiters_only(r"Arvuta \(x\) väärtus.", "Arvuta $ x $ väärtus.")

    def test_display_math_brackets(self):
        assert math_delimiters_only(r"\[a+b\]", "$$a+b$$")


class TestAsciiMathDelimiters:
    """`stem:` is Asciidoctor's default stem notation and renders as `\\$x\\$`, not `\\(x\\)`."""

    def test_escaped_dollar_delimiters(self):
        assert math_delimiters_only(r"Arvuta \$x\$ väärtus.", "Arvuta $x$ väärtus.")

    def test_escaped_dollars_with_braces_and_padding(self):
        assert math_delimiters_only(r"Arvuta \$x^2 + y^2\$ väärtus.", "Arvuta $ x^{2} + y^{2} $ väärtus.")

    def test_a_stray_backslash_outside_math_still_differs(self):
        assert not math_delimiters_only(r"Kirjuta \\ siia.", "Kirjuta siia.")


class TestBraceNormalisation:
    """pandoc writes `x^2` as `x^{2}`. Same LaTeX, different string."""

    def test_superscript(self):
        assert math_delimiters_only(r"\(x^2 + y^2\)", "$ x^{2} + y^{2} $")

    def test_subscript(self):
        assert math_delimiters_only(r"\(a_1 + a_2\)", "$ a_{1} + a_{2} $")

    def test_nested(self):
        assert math_delimiters_only(r"\(x^2^3\)", "$ x^{2^{3}} $")

    def test_already_braced_on_both_sides_is_unaffected(self):
        assert math_delimiters_only(r"\(x^{2}\)", "$ x^{2} $")

    def test_multi_character_exponent(self):
        assert math_delimiters_only(r"\(x^10\)", "$ x^{10} $")


class TestNotMathOnly:
    """The half that keeps the change honest: a real difference must still flag."""

    def test_a_changed_number_is_not_a_delimiter(self):
        assert not math_delimiters_only(r"\(x^2\)", "$ x^{3} $")

    def test_lost_words_are_not_a_delimiter(self):
        assert not math_delimiters_only(r"Arvuta \(x\) ja selgita.", "Arvuta $x$.")

    def test_a_brace_in_prose_is_not_collapsed(self):
        # No ^ or _ in front of it, so MATH_BRACE must leave it alone and the texts must differ.
        assert not math_delimiters_only("Kirjuta {nimi} siia.", "Kirjuta nimi siia.")

    def test_a_brace_group_not_attached_to_a_script_is_left_alone(self):
        assert not math_delimiters_only(r"\(f{x}\)", "$ fx $")

    def test_added_text_is_not_a_delimiter(self):
        assert not math_delimiters_only(r"\(x\)", "$x$ (vt allpool)")

    def test_empty_brace_group_is_not_collapsed(self):
        # `[^{}]+` requires content, so `x^{}` stays put and this stays a difference.
        assert not math_delimiters_only(r"\(x^\)", "$ x^{} $")
