#!/bin/sh
# Is this tiivad image fit to grade with?
#
# Run by CI before the image is published, and by the host reconciler before it is made live. Both
# run it with `--network none`, so nothing here may reach out.
set -eu

python3 /easy-smoke-expect-versions.py

# A wheel can install cleanly and still be unusable — the recurring failure in this family of images
# is a Python version the package was never built for. Importing is what catches that, and it is
# cheap enough to be worth doing on every reconcile.
python3 - <<'PY'
import tiivad
import tiivad.results          # what writes the grade an executor reads back
print("tiivad imports")
PY

echo "smoke: tiivad ok"
