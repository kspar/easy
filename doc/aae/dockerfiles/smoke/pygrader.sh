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

python3 - <<'PY'
import importlib.metadata

import grader                                   # the package python-grader's setup.py installs
print("grader", importlib.metadata.version("grader"), "imports")
PY

echo "smoke: pygrader ok"
