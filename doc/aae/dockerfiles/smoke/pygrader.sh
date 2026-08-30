#!/bin/sh
# Is this pygrader image fit to grade with?
#
# pygrader has no PyPI pin of its own — its source is a clone of kspar/python-grader at the commit
# named by `pygrader.PYTHON_GRADER_REF`, installed with setup.py. So there is no version to assert
# for the grader itself; what there is to check is that the install produced an importable package,
# and that the numpy student code relies on is in the range the pins declared.
#
# Until 2026-08-20 that clone tracked `HEAD`, so no two builds of this image were guaranteed alike
# and there was nothing to roll back to. The sha is what makes this image reproducible; this script
# is what makes it verified.
set -eu

python3 /easy-smoke-expect-versions.py

# `grader.easy` and not just `grader`, because the difference is the whole bug this check missed.
# `python3 -m grader.easy` is how every exercise here invokes the grader, and `easy.py` lives only on
# python-grader's `easy` branch — so an image built from a master commit imports `grader` perfectly
# well and then answers `No module named grader.easy` on the first submission. That is precisely what
# dev did from 2026-08-20 to 2026-08-30, with this check green throughout.
#
# Run as a module rather than imported, because importing it is not what grading does: `easy.py` does
# its work under `__main__`, and an import that succeeds proves less than the invocation the executor
# actually makes. With no tests to find it grades nothing and exits 0, which is all this needs.
python3 - <<'PY'
import importlib.metadata

import grader.easy                              # the entry point every exercise runs
print("grader", importlib.metadata.version("grader"), "imports, with grader.easy in it")
PY

python3 -m grader.easy >/dev/null

echo "smoke: pygrader ok"
