# coding=utf-8
"""What this host can grade with, and which library versions are in each image (EZ-1781).

Docker is faked throughout, as everywhere else in this suite: what is being tested is the answers
this module gives, not the daemon. The tests that matter most are the ones about *not* doing things —
not running a container when a label already answers, not creating one by name, not blocking a
request on a cold cache — because the alternative is discovering it on the host that runs student
code.
"""

import json
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import containers  # noqa: E402


class FakeLogger:
    def __init__(self):
        self.messages = []

    def info(self, message):
        self.messages.append(message)

    debug = warn = warning = error = info


class FakeImage:
    def __init__(self, image_id, tags, labels, created="2026-08-20T10:00:00Z"):
        self.id = image_id
        self.tags = tags
        self.labels = labels
        self.attrs = {"Created": created}


class FakeContainer:
    def __init__(self, payload, fail=False):
        self.payload = payload
        self.fail = fail
        self.removed = False
        self.started = False

    def start(self):
        self.started = True
        if self.fail:
            raise RuntimeError("could not start")

    def wait(self, timeout=None):
        return {"StatusCode": 0}

    def logs(self, stdout=True, stderr=False):
        # stderr must be excluded by the caller; if it ever asks for it, pip's warnings would be
        # mixed into the JSON and this returns something unparseable to make that fail loudly.
        if stderr:
            return b"WARNING: you are using pip 1.0\n" + json.dumps(self.payload).encode()
        return json.dumps(self.payload).encode()

    def remove(self, force=False):
        self.removed = True


class FakeImages:
    def __init__(self, images):
        self._images = images

    def list(self):
        return self._images


class FakeContainers:
    def __init__(self, payload, fail=False):
        self.payload = payload
        self.fail = fail
        self.created = []
        self.last = None

    def create(self, image=None, command=None, **kwargs):
        self.created.append({"image": image, "command": command, "kwargs": kwargs})
        self.last = FakeContainer(self.payload, fail=self.fail)
        return self.last


class FakeDocker:
    def __init__(self, images, pip_payload=None, fail=False):
        self.images = FakeImages(images)
        self.containers = FakeContainers(pip_payload or [], fail=fail)


@pytest.fixture(autouse=True)
def fresh_cache(tmp_path, monkeypatch):
    """Every test starts with an empty cache, and never writes to the real shared file."""
    monkeypatch.setattr(containers, "_image_cache", {"at": 0.0, "images": []})
    monkeypatch.setattr(containers, "IMAGE_CACHE_FILE", str(tmp_path / "cache.json"))
    containers._refresh_running.clear()
    yield
    containers._refresh_running.clear()


def labelled(name, declared, installed, inputs="abc123"):
    return FakeImage(
        "sha256:" + name * 8,
        [f"{name}:latest"],
        {
            containers.LABEL_DECLARED: declared,
            containers.LABEL_INSTALLED: installed,
            containers.LABEL_INPUTS: inputs,
        },
    )


# ------------------------------------------------------------------------------------------------
# Parsing what an image says about itself


def test_parses_a_pip_shaped_summary():
    assert containers.parse_versions("numpy==1.23.5 tiivad==0.0.33") == [
        {"name": "numpy", "version": "1.23.5"},
        {"name": "tiivad", "version": "0.0.33"},
    ]


def test_ignores_anything_that_is_not_an_exact_version():
    # `grader@<sha>` is a commit. Inventing a version for it would put a number on the About page
    # that no installed package agrees with.
    assert containers.parse_versions("grader@" + "a" * 40) == []
    assert containers.parse_versions("numpy~=1.23.4") == []


def test_an_empty_summary_is_not_an_error():
    assert containers.parse_versions(None) == []
    assert containers.parse_versions("") == []


def test_declared_and_installed_are_reported_side_by_side():
    merged = containers._merge(
        [{"name": "silmused", "version": "1.7.11"}],
        [{"name": "silmused", "version": "1.7.4"}],
    )
    # The whole point: the disagreement survives instead of one answer winning.
    assert merged == [{"name": "silmused", "declared": "1.7.11", "installed": "1.7.4"}]


def test_something_installed_but_not_declared_is_still_reported():
    merged = containers._merge([], [{"name": "numpy", "version": "1.23.5"}])
    assert merged == [{"name": "numpy", "declared": None, "installed": "1.23.5"}]


# ------------------------------------------------------------------------------------------------
# Reading the images


def test_a_labelled_image_needs_no_container(monkeypatch):
    fake = FakeDocker([labelled("silmused", "silmused==1.7.11", "silmused==1.7.11 psycopg2==2.9.9")])
    monkeypatch.setattr(containers.docker, "from_env", lambda: fake)

    images = containers._refresh_grading_images(FakeLogger())

    assert not fake.containers.created, "ran a container when a label already answered"
    assert images[0]["name"] == "silmused"
    assert images[0]["source"] == "label"
    assert {"name": "silmused", "declared": "1.7.11", "installed": "1.7.11"} in images[0]["libraries"]


def test_an_image_with_no_installed_label_is_asked_with_pip(monkeypatch):
    image = FakeImage(
        "sha256:" + "d" * 20, ["silmused:latest"], {containers.LABEL_DECLARED: "silmused==1.7.11"}
    )
    fake = FakeDocker([image], pip_payload=[{"name": "silmused", "version": "1.7.4"}])
    monkeypatch.setattr(containers.docker, "from_env", lambda: fake)

    images = containers._refresh_grading_images(FakeLogger())

    assert fake.containers.created, "an unlabelled image was reported without being inspected"
    assert images[0]["source"] == "pip"
    # And the mismatch this found is exactly the PR #70 failure, now visible.
    assert images[0]["libraries"] == [
        {"name": "silmused", "declared": "1.7.11", "installed": "1.7.4"}
    ]


def test_the_pip_container_is_created_by_image_id_never_by_name(monkeypatch):
    """The no-pull guard, asserted directly.

    `containers.create(image="silmused")` would make the daemon pull a missing image. This endpoint
    is read-only and unauthenticated, so it must never be a way to make a grading host fetch
    anything.
    """
    image = FakeImage("sha256:" + "e" * 20, ["silmused:latest"], {containers.LABEL_DECLARED: "silmused==1.0"})
    fake = FakeDocker([image], pip_payload=[{"name": "silmused", "version": "1.0"}])
    monkeypatch.setattr(containers.docker, "from_env", lambda: fake)

    containers._refresh_grading_images(FakeLogger())

    assert fake.containers.created[0]["image"] == image.id
    assert fake.containers.created[0]["image"] not in ("silmused", "silmused:latest")


def test_the_pip_container_gets_no_network_and_a_memory_cap(monkeypatch):
    image = FakeImage("sha256:" + "f" * 20, ["tiivad:latest"], {containers.LABEL_DECLARED: "tiivad==1.0"})
    fake = FakeDocker([image], pip_payload=[{"name": "tiivad", "version": "1.0"}])
    monkeypatch.setattr(containers.docker, "from_env", lambda: fake)

    containers._refresh_grading_images(FakeLogger())

    kwargs = fake.containers.created[0]["kwargs"]
    assert kwargs["network_disabled"] is True
    assert kwargs["mem_limit"] == "256m"


def test_the_container_is_removed_even_when_the_run_fails(monkeypatch):
    image = FakeImage("sha256:" + "0" * 20, ["tiivad:latest"], {containers.LABEL_DECLARED: "tiivad==1.0"})
    fake = FakeDocker([image], pip_payload=[], fail=True)
    monkeypatch.setattr(containers.docker, "from_env", lambda: fake)

    images = containers._refresh_grading_images(FakeLogger())

    # A stopped container left on a grading host is the sort of thing nobody notices for a year.
    assert fake.containers.last.removed
    assert images[0]["source"] == "unknown"


def test_an_image_with_no_grading_labels_is_ignored(monkeypatch):
    fake = FakeDocker([FakeImage("sha256:x", ["postgres:16"], {"maintainer": "someone"})])
    monkeypatch.setattr(containers.docker, "from_env", lambda: fake)
    assert containers._refresh_grading_images(FakeLogger()) == []


def test_an_untagged_image_is_named_by_its_id(monkeypatch):
    fake = FakeDocker([FakeImage("sha256:abcdef0123456789", [], {containers.LABEL_DECLARED: "x==1"})])
    monkeypatch.setattr(containers.docker, "from_env", lambda: fake)
    assert containers._refresh_grading_images(FakeLogger())[0]["name"]


# ------------------------------------------------------------------------------------------------
# The cache, which is what keeps Docker off the request path


def test_a_cold_cache_answers_immediately_without_waiting(monkeypatch):
    calls = []

    def slow(logger):
        calls.append(1)
        return [{"name": "tiivad"}]

    monkeypatch.setattr(containers, "_refresh_grading_images", slow)

    # The first call must return rather than block: core allows two seconds for a page render, and an
    # executor that had just restarted would otherwise spend them building this answer.
    assert containers.grading_images(FakeLogger()) == []


def test_a_warm_cache_does_no_work(monkeypatch):
    monkeypatch.setattr(
        containers, "_image_cache", {"at": containers.time(), "images": [{"name": "tiivad"}]}
    )

    def boom(logger):
        raise AssertionError("refreshed while the cache was warm")

    monkeypatch.setattr(containers, "_refresh_grading_images", boom)
    assert containers.grading_images(FakeLogger()) == [{"name": "tiivad"}]


def test_a_docker_daemon_that_is_down_yields_nothing_rather_than_raising(monkeypatch):
    def boom():
        raise RuntimeError("cannot connect to the docker daemon")

    monkeypatch.setattr(containers.docker, "from_env", boom)
    with pytest.raises(RuntimeError):
        containers._refresh_grading_images(FakeLogger())
    # ...and the caller swallows it, which is what the endpoint relies on.
    assert containers.grading_images(FakeLogger()) == []


def test_the_shared_cache_file_is_used_when_it_is_fresh(monkeypatch, tmp_path):
    path = tmp_path / "cache.json"
    path.write_text(json.dumps({"at": containers.time(), "images": [{"name": "from-file"}]}))
    monkeypatch.setattr(containers, "IMAGE_CACHE_FILE", str(path))

    def boom(logger):
        raise AssertionError("refreshed instead of reading the file another worker wrote")

    monkeypatch.setattr(containers, "_refresh_grading_images", boom)
    assert containers.grading_images(FakeLogger()) == [{"name": "from-file"}]


def test_a_stale_cache_file_is_ignored(monkeypatch, tmp_path):
    path = tmp_path / "cache.json"
    path.write_text(json.dumps({"at": 0, "images": [{"name": "ancient"}]}))
    monkeypatch.setattr(containers, "IMAGE_CACHE_FILE", str(path))
    monkeypatch.setattr(containers, "_refresh_grading_images", lambda logger: [])
    assert containers.grading_images(FakeLogger()) == []


def test_a_corrupt_cache_file_does_not_break_anything(monkeypatch, tmp_path):
    path = tmp_path / "cache.json"
    path.write_text("{ not json")
    monkeypatch.setattr(containers, "IMAGE_CACHE_FILE", str(path))
    monkeypatch.setattr(containers, "_refresh_grading_images", lambda logger: [])
    assert containers.grading_images(FakeLogger()) == []


def test_an_unwritable_cache_file_degrades_to_memory_only(monkeypatch):
    monkeypatch.setattr(containers, "IMAGE_CACHE_FILE", "/definitely/not/writable/cache.json")
    containers._write_cache_file({"at": containers.time(), "images": []})  # must not raise
