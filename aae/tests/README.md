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
| `test_tiivad_contract.py` | **the TSL compiler's output, run by the grader that consumes it** |

## The tiivad contract test

The odd one out: it reaches across to `tsl/src/test/resources/golden/*.py.expected` — the compiler's
real output, regenerated and reviewed as a diff whenever the emitter changes — feeds each script a
submission, and asks tiivad what it made of it.

That is the only check in the repo that closes the compiler-to-grader seam. Everything else is green
while the generated script is unusable, which is exactly what EZ-1774 was. Reintroducing that defect
makes **14 of these 32 fail**; `PythonSyntaxTest` stays green throughout, because the defect is valid
Python.

**The tiivad version comes from `doc/aae/dockerfiles/tiivad`**, which is what a real executor
installs. CI parses it and installs that version; `test_the_installed_tiivad_is_the_one_the_executor_uses`
asserts the two agree, so bumping the Dockerfile is enough and a wrong install fails loudly instead
of testing a version nobody grades with. Locally:

```sh
.venv/bin/pip install "tiivad==$(grep -oE 'tiivad==[0-9.]+' ../doc/aae/dockerfiles/tiivad | cut -d= -f3)"
```

Without tiivad installed those tests **skip with the install command in the reason**, so a partial
run says so rather than looking clean.

Three submission suffixes, per golden spec: `<name>.pass.py` must score 100, `<name>.fail.py` must
score below 100, and `<name>.any.py` is contract-only — tiivad must run the script without raising
and the grade is nobody's business. `escaping.json` is the third case: its expected values are the
hostile strings themselves, so whether a submission satisfies it is a question about tiivad's
phrase-extraction regex rather than about this compiler.

What it still does not cover is the **container** — the image's Python 3.10, its numpy pin, its
filesystem. tiivad is imported into the test process, so a submission that fails only under 3.10
passes here. That is the remaining half of EZ-1775.

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
