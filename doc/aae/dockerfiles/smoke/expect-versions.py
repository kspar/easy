#!/usr/bin/env python3
"""Assert that what is installed in this image is what the pins file declared.

Baked into every grading image and run by its `/easy-smoke.sh`, so the check travels with the
artefact: a host that has pulled an image can prove it without the repository, and CI can refuse to
publish one that lies about itself.

### Why this exists at all

On 2026-08-20 `doc/aae/dockerfiles/silmused` said `silmused==1.7.11` and the image on dev graded with
1.7.4, because the Ansible role only rebuilt images that were missing. Nothing noticed, because
nothing had ever compared the two. This is that comparison, run at the one moment where failing is
free — before the image is published, and again before a host makes it live.

It is also what makes the `easy.grading.installed` label trustworthy. CI reads the installed versions
out of the built image and stamps them on it; the About page then reports them without running
anything. A label is only worth reading if something refused to publish a wrong one.

### Environment

Each expectation is one variable, so a Dockerfile can pass exactly the pins it has:

    EASY_EXPECT_SILMUSED=1.7.11              exact equality
    EASY_EXPECT_NUMPY_COMPATIBLE=1.23.4      `~=1.23.4`, i.e. >=1.23.4 and <1.24

The second form exists because `numpy~=1.23.4` is a range, not a pin: which 1.23.x lands is decided
at build time by pip, and asserting equality would fail the moment 1.23.6 shipped. Asserting the
range is the strongest true statement available, and the exact version is recorded on the label
rather than demanded here.

Exits non-zero, listing every problem rather than the first — a build that has two wrong versions
should say so once.
"""

import importlib.metadata
import os
import sys

PREFIX = "EASY_EXPECT_"
COMPATIBLE_SUFFIX = "_COMPATIBLE"


def parts(version):
    """Leading numeric components, so `1.23.4` and `1.23.4.post1` compare sensibly."""
    out = []
    for chunk in version.replace("-", ".").split("."):
        if chunk.isdigit():
            out.append(int(chunk))
        else:
            break
    return tuple(out)


def installed(package):
    try:
        return importlib.metadata.version(package)
    except importlib.metadata.PackageNotFoundError:
        return None


def from_declared(summary):
    """`silmused==1.7.11 numpy~=1.23.4` -> the same expectations the `EASY_EXPECT_*` variables carry.

    CI stamps that string into the finished image as `EASY_GRADING_DECLARED`, so a published image can
    check itself with nothing but `docker run … /easy-smoke.sh` — which is what the production
    promotion runbook asks an operator to do, and what this file's docstring has always claimed.
    Before it existed the claim was false: a label is metadata *about* an image and is invisible to a
    process inside it, so the script found no expectations and exited 1.

    CI's own pre-publication run still passes explicit variables, because at that point the image has
    not been stamped yet — there is nothing to read.
    """
    out = {}
    for token in (summary or "").split():
        for operator, suffix in (("==", ""), ("~=", COMPATIBLE_SUFFIX)):
            name, sep, version = token.partition(operator)
            if sep and name and version:
                out[f"{PREFIX}{name.upper()}{suffix}"] = version
                break
    return out


def main():
    expectations = {k: v for k, v in os.environ.items() if k.startswith(PREFIX) and v}
    if not expectations:
        expectations = from_declared(os.environ.get("EASY_GRADING_DECLARED"))
    if not expectations:
        # Not "nothing to check, fine". An image whose smoke test was handed no expectations is an
        # image nobody is checking, and silently passing would make the whole gate decorative.
        print(
            f"no {PREFIX}* variables were set and this image carries no EASY_GRADING_DECLARED, so "
            f"nothing was verified.",
            file=sys.stderr,
        )
        return 1

    problems = []
    for key, expected in sorted(expectations.items()):
        name = key[len(PREFIX):]
        compatible = name.endswith(COMPATIBLE_SUFFIX)
        if compatible:
            name = name[: -len(COMPATIBLE_SUFFIX)]
        package = name.lower()

        actual = installed(package)
        if actual is None:
            problems.append(f"{package}: declared {expected}, but it is not installed at all")
            continue

        if compatible:
            want, got = parts(expected), parts(actual)
            # `~=1.23.4` means >=1.23.4 within the same 1.23 series.
            if got[:2] != want[:2] or got < want:
                problems.append(
                    f"{package}: declared ~={expected}, installed {actual} — outside the range"
                )
            else:
                print(f"{package} {actual} satisfies ~={expected}")
        elif actual != expected:
            problems.append(f"{package}: declared {expected}, installed {actual}")
        else:
            print(f"{package} {actual}")

    for p in problems:
        print(p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
