# coding=utf-8
"""Shared fixtures, and the one import trick this suite needs.

`server.py` does `from containers import grade_submission`, an implicit-relative import that only
resolves when `aae/` itself is on `sys.path` — which is how gunicorn runs it (`start-executor.sh`
sets the working directory). Tests run from the repo root or from `aae/`, so the path is added here
rather than in every test file.

Note what this means: **the suite exercises the modules exactly as deployed**, rather than a
packaged variant that behaves differently. Turning `aae/` into a package to satisfy the test runner
would be testing something the executor never runs.
"""
import os
import sys

import pytest

AAE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if AAE_ROOT not in sys.path:
    sys.path.insert(0, AAE_ROOT)


class RecordingLogger:
    """A logger that keeps what it was told, so a test can assert on it instead of on stderr."""

    def __init__(self):
        self.messages = []

    def _record(self, level):
        def log(msg, *args, **kwargs):
            self.messages.append((level, str(msg)))
        return log

    def __getattr__(self, name):
        # debug / info / warning / warn / error / exception, without listing them — `containers.py`
        # calls `logger.warn`, which is deprecated in the stdlib and would be an AttributeError on a
        # stricter fake.
        return self._record(name)

    def text(self):
        return "\n".join(m for _, m in self.messages)


@pytest.fixture
def logger():
    return RecordingLogger()


@pytest.fixture(autouse=True)
def isolated_grading_image_cache(tmp_path, monkeypatch):
    """Neutralise `containers`' grading-image cache for every test in this suite.

    Two pieces of global state, and both leak. The in-memory `_image_cache` is a module attribute, so
    one test populating it decides what a later one sees. Worse, the file cache defaults to a path in
    the machine's tempdir — shared between *runs* — so a test asserting the endpoint answers `[]`
    passes on a fresh CI runner and fails on a laptop that has run the suite before. Found exactly
    that way round, which is the direction that hides a flake rather than showing it.

    Autouse and in conftest rather than in one test file, because anything that touches `/v1/version`
    reads this cache whether it means to or not.
    """
    import containers

    monkeypatch.setattr(containers, "_image_cache", {"at": 0.0, "images": []})
    monkeypatch.setattr(containers, "IMAGE_CACHE_FILE", str(tmp_path / "grading-images.json"))
    containers._refresh_running.clear()
    yield
    containers._refresh_running.clear()


@pytest.fixture
def client():
    """A Flask test client, with exception propagation off so error handling is the app's own."""
    from server import app

    app.config.update(TESTING=False)
    with app.test_client() as c:
        yield c
