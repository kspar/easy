#!/bin/sh
# Is this imgrec image fit to grade with?
#
# imgrec is pygrader plus image handling, and it builds `FROM pygrader` — so it inherits whatever
# that image is, which is why `bin/pins.py digest` includes its base's digest. Before that, a
# pygrader rebuild left imgrec silently sitting on an old base; the Ansible role documented the gap
# and declined to fix it.
#
# Pillow and requests were entirely unpinned until 2026-08-20. Their pins were captured from what pip
# resolved rather than chosen by anyone, so this script's job is to make sure the capture stays true.
set -eu

python3 /easy-smoke-expect-versions.py

python3 - <<'PY'
import requests                                 # noqa: F401 — imported to prove it loads
from PIL import Image

# Not just an import: Pillow without its compiled imaging extension imports fine and fails the
# moment anything touches a pixel, which on a grading host means failing a student's submission
# rather than failing this.
im = Image.new("RGB", (4, 4), (255, 0, 0))
assert im.getpixel((0, 0)) == (255, 0, 0)
print("PIL and requests import, and PIL can make an image")
PY

# imgrec's whole reason to exist is headless rendering, and xvfb is the part of that which is not a
# Python package — so it is the part nothing else would notice was missing.
command -v Xvfb >/dev/null
command -v gs >/dev/null

echo "smoke: imgrec ok"
