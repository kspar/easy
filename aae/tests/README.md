# aae tests

```sh
cd aae
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest tests -q
```

Runs in CI as the **Executor (Python)** job. Needs no Docker daemon and takes under a second.

## What is faked, and why that is the right line

`docker` is replaced in every test that would reach it. What is worth testing here is **the
directory `aae` lays out** — which files, under which names, with which permissions — and **the
answers it gives**: the grade parsed out of a container's stdout, and the Estonian sentence a student
sees when there is no grade. All of that is ours. Building a real image per test would make this the
slowest thing in the repo and would be testing Docker.

The consequence is worth stating: **nothing here proves a real grading run works.** That is EZ-1775 —
running a compiler-generated script against tiivad and asserting the grade — and it is the one gap
this suite deliberately leaves.

## Files

| | |
| --- | --- |
| `test_parse_assessment_output.py` | both grader output formats, and every way they can be malformed |
| `test_grade_submission.py` | the directory handed to Docker, and that the temp dir is removed on the failure path too |
| `test_grade_endpoint.py` | `POST /v1/grade` validation, and the run-status → student-message mapping |
| `test_version_and_status.py` | version reporting fallbacks, and the OOM heuristic |

`conftest.py` puts `aae/` on `sys.path`, because `server.py` does `from containers import ...` — an
implicit-relative import that only resolves when `aae/` is the working directory, which is how
gunicorn runs it. Making `aae` a package to please the test runner would mean testing a layout the
executor never uses.

## If you are mutation-testing this

Two traps, both of which report in the reassuring direction:

- `export PYTHONDONTWRITEBYTECODE=1` and delete `__pycache__` between runs. A mutation of the same
  byte size, restored within a second, leaves `__pycache__` serving the stale module — so a mutation
  can appear not to matter when it does.
- Check the mutation actually applied (`grep -q` for the new text) before reading the result. A
  regex that quietly matched nothing looks exactly like a test suite that noticed nothing.

Both of these happened while writing this suite. See `doc/testing-log.md`.
