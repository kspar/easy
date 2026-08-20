"""Temporary: the pins files and the Dockerfile literals must not disagree.

### Delete this file when the Dockerfiles take their versions as build args

Right now the same version is written twice — once in `doc/aae/pins/dev.yml` and once as a literal in
`doc/aae/dockerfiles/*`. That is on purpose and it is temporary. Introducing a second source of truth
and *then* moving everything onto it is the only sequence where nothing is ever briefly broken, and
this file is the machine that keeps the two honest in the meantime.

Once each Dockerfile declares `ARG SILMUSED_VERSION` and CI passes the value in, the literals are
gone, there is one truth, and this file has nothing left to compare. Delete it in the same commit —
leaving it would mean silently asserting nothing.

### Why only dev

The Dockerfiles describe what dev builds today, so dev is the side that can be checked. Production's
pins are a belief that nobody has yet compared against the production host — see the header of
`doc/aae/pins/prod.yml` — so asserting anything about them here would be inventing confidence.

### Why three pins are exempt

Two of the four images have no literal to agree with, which is the whole reason they are being
brought into the pins file:

  * `pygrader.PYTHON_GRADER_REF` — today the source is fetched at `HEAD`
    (`ansible/roles/executor/defaults/main.yml`), so there is no version anywhere to compare a sha
    against. The pin is new information, not a restatement.
  * `imgrec.PILLOW_VERSION` and `imgrec.REQUESTS_VERSION` — the Dockerfile installs `Pillow` and
    `requests` with no version at all. These were captured from what pip resolved on 2026-08-20;
    there is nothing in the repo that could confirm or contradict them.
"""

from __future__ import annotations

import os
import re
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pins  # noqa: E402

# (pins key, dockerfile, the operator the Dockerfile uses). The operator matters: `==` is an exact
# pin and `~=` is a compatible-release range, and asserting the wrong one would pass while the
# Dockerfile said something different from what we think it says.
AGREEMENTS = [
    ("tiivad.TIIVAD_VERSION", "tiivad", "tiivad", "=="),
    ("tiivad.NUMPY_SPEC", "tiivad", "numpy", "~="),
    ("silmused.SILMUSED_VERSION", "silmused", "silmused", "=="),
    ("silmused.PSYCOPG2_VERSION", "silmused", "psycopg2", "=="),
    ("pygrader.NUMPY_SPEC", "pygrader", "numpy", "~="),
]

EXEMPT = {
    "pygrader.PYTHON_GRADER_REF",
    "imgrec.PILLOW_VERSION",
    "imgrec.REQUESTS_VERSION",
}


def dockerfile(name: str) -> str:
    with open(os.path.join(pins.DOCKERFILE_DIR, name), encoding="utf-8") as f:
        return f.read()


@pytest.mark.parametrize("key,image,package,operator", AGREEMENTS)
def test_the_dockerfile_installs_what_the_pins_file_says(key, image, package, operator):
    expected = pins.load("dev")[key]
    text = dockerfile(image)
    found = re.findall(rf"{re.escape(package)}{re.escape(operator)}([0-9][0-9.]*)", text)
    assert found, f"{image} does not install {package}{operator}<version> at all"
    assert all(v == expected for v in found), (
        f"doc/aae/pins/dev.yml says {key} is {expected}, but doc/aae/dockerfiles/{image} "
        f"installs {package}{operator}{found}. While both exist they must agree; change both, or "
        f"finish moving {image} onto build args and delete this file."
    )


def test_every_dev_pin_is_either_checked_or_knowingly_exempt():
    """No pin may quietly avoid the comparison by not being listed above.

    Without this, adding a pin and forgetting to add an agreement row would leave it unchecked, and
    the file would look like it was doing more than it was.
    """
    checked = {key for key, *_ in AGREEMENTS}
    pinned = {k for k in pins.load("dev") if k not in pins.META_KEYS}
    unaccounted = pinned - checked - EXEMPT
    assert not unaccounted, (
        f"these pins are neither checked against a Dockerfile nor listed as exempt: "
        f"{sorted(unaccounted)}"
    )
