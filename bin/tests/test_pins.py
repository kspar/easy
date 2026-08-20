"""Tests for bin/pins.py.

Most of what this module does is decide whether a pull request from someone with no write access to a
public repository may merge itself. So the negative cases are the point: every test below named
`test_refuses_*` is a hole somebody could otherwise walk through, and a permissive bug in
`validate_change` is worth more attention than a wrong version number ever could be.

The grammar and digest tests run against the real files in doc/aae/, deliberately — a parser that
only ever sees fixtures is a parser nobody has pointed at the thing it is for.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pins  # noqa: E402

ENVIRONMENTS = ("dev", "prod")

ALLOW = {
    "dev.silmused": ["nuubis"],
    "dev.tiivad": ["KarmoSaviauk"],
    "dev.pygrader": [],
    "prod.silmused": [],
}


def patch(before: str, after: str) -> str:
    """A minimal unified diff changing one line, as GitHub's files API reports it."""
    return f"@@ -1,1 +1,1 @@\n-{before}\n+{after}"


def bump(key="silmused.SILMUSED_VERSION", old="1.7.11", new="1.7.12"):
    return patch(f'{key}: "{old}"', f'{key}: "{new}"')


# ------------------------------------------------------------------------------------------------
# Grammar


def test_parses_the_real_pins_files():
    for env in ENVIRONMENTS:
        values = pins.load(env)
        assert values["environment"] == env
        assert values["silmused.SILMUSED_VERSION"]


def test_accepts_comments_and_blank_lines():
    got = pins.parse('# a comment\n\n   \nx.Y: "1.2"\n', source="t")
    assert got == {"x.Y": "1.2"}


def test_refuses_an_unquoted_value():
    # The important one: unquoted, YAML reads 1.10 as 1.1, so accepting this would install a real
    # but wrong release rather than failing.
    with pytest.raises(pins.PinsError, match="must be quoted"):
        pins.parse("silmused.SILMUSED_VERSION: 1.10\n", source="t")


def test_refuses_a_duplicate_key():
    with pytest.raises(pins.PinsError, match="set twice"):
        pins.parse('a.B: "1"\na.B: "2"\n', source="t")


def test_refuses_a_line_it_does_not_understand():
    with pytest.raises(pins.PinsError, match="not a"):
        pins.parse("just some prose\n", source="t")


def test_refuses_nested_yaml():
    with pytest.raises(pins.PinsError):
        pins.parse('dev:\n  silmused: "1.7.11"\n', source="t")


def test_refuses_an_environment_that_disagrees_with_its_filename():
    text = 'schema: "1"\nenvironment: "prod"\nsilmused.SILMUSED_VERSION: "1.7.11"\n'
    with pytest.raises(pins.PinsError, match="but is named"):
        pins.load_text(text, env="dev", source="t")


def test_refuses_a_pin_for_an_image_that_does_not_exist():
    text = 'schema: "1"\nenvironment: "dev"\nnosuchimage.VERSION: "1.0"\n'
    with pytest.raises(pins.PinsError, match="names no grading image"):
        pins.load_text(text, env="dev", source="t")


def test_refuses_a_short_git_ref():
    with pytest.raises(pins.PinsError, match="40-character"):
        pins.check_value("pygrader.PYTHON_GRADER_REF", "7105517", source="t")


def test_refuses_a_non_version_value():
    with pytest.raises(pins.PinsError, match="not a plain dotted version"):
        pins.check_value("silmused.SILMUSED_VERSION", "1.7.11.post1", source="t")


# ------------------------------------------------------------------------------------------------
# Images, ordering, args


def test_every_image_has_a_dockerfile():
    assert set(pins.images()) == {"tiivad", "silmused", "pygrader", "imgrec"}


def test_imgrec_builds_from_pygrader():
    assert pins.base_of("imgrec") == "pygrader"
    assert pins.base_of("silmused") is None


def test_order_puts_a_base_before_what_builds_on_it():
    order = pins.order()
    assert set(order) == set(pins.images())
    assert order.index("pygrader") < order.index("imgrec")


def test_args_are_the_pins_for_that_image_only():
    args = pins.args_for("dev", "silmused")
    assert args["SILMUSED_VERSION"]
    assert not any(k.startswith("TIIVAD") for k in args)


# ------------------------------------------------------------------------------------------------
# Digest


def test_digest_is_stable_and_short():
    a, b = pins.digest("dev", "silmused"), pins.digest("dev", "silmused")
    assert a == b and len(a) == 12


def test_digest_changes_when_a_pin_changes():
    base = pins.load("dev")
    moved = dict(base, **{"silmused.SILMUSED_VERSION": "1.7.12"})
    assert pins.digest("dev", "silmused", base) != pins.digest("dev", "silmused", moved)


def test_digest_changes_when_the_rebuild_serial_changes():
    base = pins.load("dev")
    moved = dict(base, **{"rebuild.SERIAL": "999"})
    assert pins.digest("dev", "silmused", base) != pins.digest("dev", "silmused", moved)


def test_digest_of_a_child_changes_when_its_base_changes():
    # The imgrec/pygrader gap the ansible role documented and declined to fix. If this ever passes
    # trivially, the recursion in digest() has been lost and a pygrader bump would silently leave
    # imgrec on an old base.
    base = pins.load("dev")
    moved = dict(base, **{"pygrader.NUMPY_SPEC": "1.23.5"})
    assert pins.digest("dev", "imgrec", base) != pins.digest("dev", "imgrec", moved)


def test_digest_ignores_the_environment_name():
    # Two environments pinning the same versions must resolve to one artefact, so that promoting
    # what dev proved is a retag rather than a rebuild.
    dev, prod = pins.load("dev"), pins.load("prod")
    if {k: v for k, v in dev.items() if k != "environment"} == {
        k: v for k, v in prod.items() if k != "environment"
    }:
        assert pins.digest("dev", "silmused") == pins.digest("prod", "silmused")


def test_digest_is_insensitive_to_pin_ordering():
    base = pins.load("dev")
    shuffled = dict(reversed(list(base.items())))
    assert pins.digest("dev", "silmused", base) == pins.digest("dev", "silmused", shuffled)


# ------------------------------------------------------------------------------------------------
# validate_change — the positives


def _validate(**kw):
    kw.setdefault("filenames", ["doc/aae/pins/dev.yml"])
    kw.setdefault("patch", bump())
    kw.setdefault("author", "nuubis")
    kw.setdefault("allowlist", ALLOW)
    return pins.validate_change(**kw)


def test_allows_a_version_bump_from_a_permitted_author():
    assert _validate() == [("silmused.SILMUSED_VERSION", "1.7.11", "1.7.12")]


def test_allows_a_downgrade():
    # Rollback is the case that matters most, and GitHub's Revert button produces exactly this.
    assert _validate(patch=bump(old="1.7.11", new="1.7.4"))


# ------------------------------------------------------------------------------------------------
# validate_change — the negatives, one per hole


def test_refuses_two_changed_files():
    # Also what stops a pull request editing a workflow and a pin together, which is why a green
    # check with the right name can never be the merge decision on its own.
    with pytest.raises(pins.PinsError, match="changes 2 files"):
        _validate(filenames=["doc/aae/pins/dev.yml", ".github/workflows/pins-guard.yml"])


def test_refuses_a_file_outside_the_pins_directory():
    with pytest.raises(pins.PinsError, match="not a file under"):
        _validate(filenames=["doc/aae/dockerfiles/silmused"])


def test_refuses_a_renamed_key():
    with pytest.raises(pins.PinsError, match="renames"):
        _validate(patch=patch('a.B: "1.0"', 'a.C: "1.0"'))


def test_refuses_an_added_key():
    with pytest.raises(pins.PinsError, match="removes 1 line"):
        _validate(patch='@@ -1,1 +1,2 @@\n-a.B: "1.0"\n+a.B: "1.0"\n+a.C: "2.0"')


def test_refuses_a_removed_key():
    with pytest.raises(pins.PinsError, match="removes 2 line"):
        _validate(patch='@@ -1,2 +1,1 @@\n-a.B: "1.0"\n-a.C: "2.0"\n+a.B: "1.0"')


def test_refuses_a_patch_that_changes_nothing():
    with pytest.raises(pins.PinsError, match="changes no existing line"):
        _validate(patch='@@ -1,0 +1,1 @@\n+a.B: "1.0"')


def test_refuses_shell_injection_in_a_value():
    with pytest.raises(pins.PinsError, match="not a plain dotted version"):
        _validate(patch=bump(new="1.7.12; rm -rf /"))


def test_refuses_a_prerelease():
    with pytest.raises(pins.PinsError, match="not a plain dotted version"):
        _validate(patch=bump(new="2.0.0rc1"))


def test_refuses_a_post_release():
    with pytest.raises(pins.PinsError, match="not a plain dotted version"):
        _validate(patch=bump(new="1.7.11.post1"))


def test_refuses_a_change_to_the_rebuild_serial():
    with pytest.raises(pins.PinsError, match="never auto-merged"):
        _validate(patch=patch('rebuild.SERIAL: "1"', 'rebuild.SERIAL: "2"'))


def test_refuses_a_change_to_the_schema():
    with pytest.raises(pins.PinsError, match="never auto-merged"):
        _validate(patch=patch('schema: "1"', 'schema: "2"'))


def test_refuses_a_pull_request_against_another_branch():
    with pytest.raises(pins.PinsError, match="not master"):
        _validate(base_ref="dev-releases")


def test_refuses_moving_the_pygrader_ref():
    with pytest.raises(pins.PinsError, match="needs a person"):
        _validate(
            patch=patch(
                'pygrader.PYTHON_GRADER_REF: "' + "a" * 40 + '"',
                'pygrader.PYTHON_GRADER_REF: "' + "b" * 40 + '"',
            ),
        )


# ------------------------------------------------------------------------------------------------
# validate_change — authorisation, which is the part that must never be permissive


def test_refuses_an_unknown_author():
    with pytest.raises(pins.PinsError, match="not allowed"):
        _validate(author="some-stranger")


def test_refuses_a_dev_author_on_the_production_file():
    # nuubis may bump silmused on dev. That must not carry to production.
    with pytest.raises(pins.PinsError, match="nobody is currently allowed"):
        _validate(filenames=["doc/aae/pins/prod.yml"], author="nuubis")


def test_refuses_an_author_permitted_for_another_image():
    with pytest.raises(pins.PinsError, match="not allowed"):
        _validate(patch=bump("tiivad.TIIVAD_VERSION", "0.0.33", "0.0.34"), author="nuubis")


def test_refuses_everyone_when_the_list_is_empty():
    with pytest.raises(pins.PinsError, match="nobody is currently allowed"):
        _validate(patch=bump("pygrader.NUMPY_SPEC", "1.23.4", "1.23.5"), author="nuubis")


def test_refuses_an_image_absent_from_the_allowlist():
    # A typo in the allowlist must fail closed, not open.
    with pytest.raises(pins.PinsError, match="nobody is currently allowed"):
        _validate(patch=bump("imgrec.PILLOW_VERSION", "12.3.0", "12.4.0"), author="nuubis")


def test_the_real_allowlist_parses_and_grants_nothing_on_production():
    allow = pins.load_allowlist()
    assert allow["dev.silmused"] == ["nuubis"]
    assert all(not v for k, v in allow.items() if k.startswith("prod."))


# ------------------------------------------------------------------------------------------------
# check-exists


def test_package_name_is_derived_from_the_build_arg():
    assert pins.package_for("SILMUSED_VERSION") == "silmused"
    assert pins.package_for("PSYCOPG2_VERSION") == "psycopg2"
    # A range has no single release to look up.
    assert pins.package_for("NUMPY_SPEC") is None


def test_a_missing_version_is_reported(monkeypatch):
    import urllib.error

    def boom(url, timeout=0):
        raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)

    monkeypatch.setattr(pins.urllib.request, "urlopen", boom)
    problems = pins._check_pypi("k", "silmused", "9.9.9", timeout=1)
    assert problems and "not on PyPI" in problems[0]


def test_a_network_failure_fails_closed(monkeypatch):
    # "We could not check" must never merge. If this test ever fails, check-exists has become a
    # gate that opens when PyPI is having a bad day.
    def boom(url, timeout=0):
        raise OSError("no route to host")

    monkeypatch.setattr(pins.urllib.request, "urlopen", boom)
    assert pins._check_pypi("k", "silmused", "1.7.11", timeout=1)


def test_a_published_version_is_accepted(monkeypatch):
    class R:
        def read(self):
            return json.dumps({"urls": [{"yanked": False}]}).encode()

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

    monkeypatch.setattr(pins.urllib.request, "urlopen", lambda url, timeout=0: R())
    assert pins._check_pypi("k", "silmused", "1.7.11", timeout=1) == []


def test_a_yanked_version_is_refused(monkeypatch):
    class R:
        def read(self):
            return json.dumps(
                {"urls": [{"yanked": True, "yanked_reason": "broken sdist"}]}
            ).encode()

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

    monkeypatch.setattr(pins.urllib.request, "urlopen", lambda url, timeout=0: R())
    problems = pins._check_pypi("k", "silmused", "1.7.11", timeout=1)
    assert problems and "yanked" in problems[0]


# ------------------------------------------------------------------------------------------------
# The CLI, since that is what CI and Ansible actually call


def _cli(*args):
    return subprocess.run(
        [sys.executable, os.path.join(pins.REPO_ROOT, "bin", "pins.py"), *args],
        capture_output=True, text=True, check=False,
    )


def test_cli_get_prints_one_value():
    out = _cli("get", "silmused.SILMUSED_VERSION")
    assert out.returncode == 0
    assert out.stdout.strip() == pins.load("dev")["silmused.SILMUSED_VERSION"]


def test_cli_args_prints_build_args():
    out = _cli("args", "silmused")
    assert out.returncode == 0
    assert "SILMUSED_VERSION=" in out.stdout


def test_cli_order_lists_bases_first():
    out = _cli("order")
    names = out.stdout.split()
    assert names.index("pygrader") < names.index("imgrec")


def test_cli_validate_patch_reads_the_github_files_payload(tmp_path):
    payload = [{"filename": "doc/aae/pins/dev.yml", "patch": bump()}]
    f = tmp_path / "files.json"
    f.write_text(json.dumps(payload))
    out = _cli("validate-patch", str(f), "--author", "nuubis")
    assert out.returncode == 0, out.stderr
    assert "1.7.11 -> 1.7.12" in out.stdout


def test_cli_validate_patch_rejects_a_stranger(tmp_path):
    payload = [{"filename": "doc/aae/pins/dev.yml", "patch": bump()}]
    f = tmp_path / "files.json"
    f.write_text(json.dumps(payload))
    out = _cli("validate-patch", str(f), "--author", "some-stranger")
    assert out.returncode == 1
    assert "not auto-mergeable" in out.stderr
