# coding=utf-8

import enum
import json
import os
import os.path
import re
import tempfile
import threading
from time import time, sleep

import docker
import docker.errors

# Interval in seconds for polling Docker daemon for container status
POLL_INTERVAL_SEC = 0.5

# --- reporting which grading libraries this host actually has (EZ-1781) -----------------------------
#
# "Which silmused graded this submission?" used to need an ssh session. The version existed only as a
# literal in a Dockerfile, `container_image` has one column, and nothing anywhere recorded what was
# installed — which is how dev spent a fortnight advertising silmused 1.7.11 while grading with 1.7.4.
#
# So the executor answers it. Core asks once every five minutes and passes it to the About page.
#
# ### Declared and installed are different questions
#
# `easy.grading.declared` is what the pins file asked for. `easy.grading.installed` is what pip
# actually resolved, read out of the finished image by CI. They agree by construction for anything the
# pipeline built, because the image's own smoke check refuses to publish otherwise — so a
# *disagreement* is the interesting signal, and it is the one nobody could previously see.
#
# ### Why a label, and why a container as a last resort
#
# A label is answered by `docker image inspect` in about a millisecond, and cannot drift from the
# image it is attached to. Production's images were built by hand before any of this existed, though,
# and carry no labels at all — and production is exactly where version questions get asked. So an
# unlabelled image is inspected the only way Docker allows: `pip list` inside a throwaway container.
#
# That is bounded hard, because this is the machine that runs student code: no network, capped memory,
# killed after a timeout, removed in a `finally`, at most a few images, once an hour — and created
# **by image id, never by name**, so this can never become a way to make a grading host fetch
# something. It retires itself: every image the pipeline builds hits the label path instead.

LABEL_PREFIX = "easy.grading."
LABEL_DECLARED = LABEL_PREFIX + "declared"
LABEL_INSTALLED = LABEL_PREFIX + "installed"
LABEL_INPUTS = LABEL_PREFIX + "inputs"

# An hour. Nothing here changes except when a deploy changes it, and the About page showing an
# hour-old answer is not a problem worth paying for on every request.
IMAGE_CACHE_TTL_SEC = 60 * 60
# Enough for the four grading images and a little room; a host with hundreds of images is not this
# script's problem to enumerate.
IMAGE_INSPECT_LIMIT = 20
PIP_TIMEOUT_SEC = 20
# Shared between gunicorn workers. The unit sets PrivateTmp=true, so this is private to the service
# and wiped on restart — which is the behaviour wanted: a restart re-asks. Without it ~30 workers on
# a production host would each refresh separately.
IMAGE_CACHE_FILE = os.environ.get(
    "EASY_GRADING_IMAGE_CACHE", os.path.join(tempfile.gettempdir(), "easy-grading-images.json")
)

# Overridden by roles/executor_images from the same list that decides what the host pulls. The
# default is what every environment has run for years.
DEFAULT_GRADING_IMAGE_NAMES = ("tiivad", "silmused", "pygrader", "imgrec")

_image_cache = {"at": 0.0, "images": []}
_image_cache_lock = threading.Lock()
_refresh_running = threading.Event()

DOCKERFILE_TEMPLATE = '''FROM {}
COPY student-submission /student-submission
COPY evaluate.sh /
CMD /evaluate.sh'''

# Raw docker daemon status strings
RUNNING_STATUS = 'running'
EXITED_STATUS = 'exited'


class AssetNameError(ValueError):
    """An exercise asset whose file name is not a file name."""


def _checked_asset_name(file_name):
    """
    An asset name has to be a plain file name, and this is where that is enforced.

    Assets are written into the submission directory with `os.path.join`, which does exactly what it
    is asked and nothing more: `..` walks up, and an **absolute path discards the base entirely**.
    Neither is theoretical, and the interesting target is one level up rather than the file system at
    large. `student-submission/`'s parent is the Docker build context — the directory `Dockerfile`
    and `evaluate.sh` live in — and the asset loop runs *after* the Dockerfile is written. So an asset
    named `../Dockerfile` replaces the Dockerfile that `images.build(path=source_dir)` then builds,
    which is an arbitrary `FROM` and arbitrary `RUN` on the host that grades student code.

    The rule is "a plain file name" rather than "resolves inside the directory", and the difference
    matters twice. A name containing a separator cannot work today anyway — the subdirectory does not
    exist, so `open` raises — so nothing legitimate is being taken away, and a containment check
    phrased on the resolved path would quietly start permitting subdirectories the moment anyone added
    an `os.makedirs`. The stricter rule also needs no path resolution to read.

    What is deliberately still allowed is an asset **overwriting** `submission.py` or `lahendus.py`.
    That is a documented feature — an exercise can supply the file the student's submission would
    otherwise be — and `test_an_asset_may_overwrite_the_submission_filename` pins it. Collision inside
    the directory is intended; leaving the directory is not.

    Raises rather than skipping or sanitising. A skipped asset grades the submission against
    incomplete tests and reports a grade as if nothing happened, and a silently renamed one does the
    same; both turn a broken exercise into wrong marks. Failing means the teacher finds out.
    """
    if file_name != os.path.basename(file_name) or file_name in ('', '.', '..') or '\\' in file_name:
        raise AssetNameError('Asset file name must be a plain file name, got {!r}'.format(file_name))
    return file_name


def grade_submission(submission, grading_script, assets, base_image_name, max_run_time_sec, max_mem_MB, logger,
                     request_id):
    """
    :param submission: str, submission content
    :param grading_script: str, grading script content
    :param assets: list[tuple[str, str]], list on pairs (file_name, file_content), one pair for each asset
    :param base_image_name: str, name of base docker image that contains dependencies for the grading script,
                                note that this image must already exist
    :param max_run_time_sec: int, maximum run time of the container / grading script in seconds
    :param max_mem_MB: int, maximum memory usage of the container in megabytes, must be >= 4
    :param logger: logger object, must have standard debug, info etc methods

    :return pair (run_status: RunStatus, raw_output: str)
    """

    # Create temporary dir for this submission and write submission data as files
    with tempfile.TemporaryDirectory() as student_dir:
        with open(os.path.join(student_dir, 'Dockerfile'), mode='w', encoding='utf-8') as docker_file:
            docker_file.write(DOCKERFILE_TEMPLATE.format(base_image_name))

        with open(os.path.join(student_dir, 'evaluate.sh'), mode='w', encoding='utf-8') as evaluate_file:
            evaluate_file.write(grading_script)

        # Grading script needs read and execution permissions
        os.chmod(os.path.join(student_dir, 'evaluate.sh'), 0o500)

        os.mkdir(os.path.join(student_dir, 'student-submission'))

        # New automatic tests use the lahendus.py file because that produces nicer error messages
        # but keeping submission.py as well for legacy tests
        with open(os.path.join(student_dir, 'student-submission', 'submission.py'), mode='w',
                  encoding='utf-8') as submission_file:
            submission_file.write(submission)

        with open(os.path.join(student_dir, 'student-submission', 'lahendus.py'), mode='w',
                  encoding='utf-8') as submission_file:
            submission_file.write(submission)

        for asset in assets:
            with open(os.path.join(student_dir, 'student-submission', _checked_asset_name(asset[0])), mode='w',
                      encoding='utf-8') as asset_file:
                asset_file.write(asset[1])

        return _run_in_container(student_dir, max_run_time_sec, max_mem_MB, logger, request_id)


def _run_in_container(source_dir, max_run_time_sec, max_mem_MB, logger, request_id):
    docker_client = docker.from_env()

    # Create image
    image_id = docker_client.images.build(path=source_dir, rm=True)[0].id
    logger.debug('Built image {} ({})'.format(image_id, request_id))

    # Create and run container
    container = docker_client.containers.run(image_id, detach=True, mem_limit='{}m'.format(max_mem_MB),
                                             network_mode='host')
    logger.debug("Started container {} ({})".format(container.short_id, request_id))
    start_time = time()
    i = 0

    while True:
        # Reload container status from docker daemon
        container.reload()
        status = container.status
        if status == EXITED_STATUS:
            logger.info('Container exited ({})'.format(request_id))
            run_status = RunStatus.SUCCESS
            break
        elif status == RUNNING_STATUS:
            logger.debug('Container still running... iteration {} ({})'.format(i, request_id))
        else:
            logger.error('Unexpected container status {} ({})'.format(status, request_id))

        if time() - start_time > max_run_time_sec:
            logger.warn('Timeout, killing container ({})'.format(request_id))
            try:
                container.kill()
            except docker.errors.APIError as e:
                logger.error("{} ({})".format(e, request_id))
            run_status = RunStatus.TIME_EXCEEDED
            break

        i += 1
        sleep(POLL_INTERVAL_SEC)

    output = container.logs().decode('utf-8')
    logger.debug('Removing container {} ({})'.format(container.short_id, request_id))
    container.remove()
    logger.debug('Removing image {} ({})'.format(image_id, request_id))
    try:
        docker_client.images.remove(image=image_id)
    except docker.errors.APIError as e:
        logger.error('{}, ({})'.format(e, request_id))

    if _was_memory_killed(output):
        run_status = RunStatus.MEM_EXCEEDED

    return run_status, output


def _was_memory_killed(output):
    # Assume the process was killed by OOM killer if the last non-empty lowercased line of the output contains 'killed'
    return 'killed' in output.strip().split('\n')[-1].lower()


@enum.unique
class RunStatus(enum.Enum):
    SUCCESS = enum.auto()
    TIME_EXCEEDED = enum.auto()
    MEM_EXCEEDED = enum.auto()


# --- grading image reporting ------------------------------------------------------------------------

def parse_versions(summary):
    """`numpy==1.23.5 tiivad==0.0.33` -> [{"name": ..., "version": ...}], ignoring anything else.

    `grader@<sha>` and the like are skipped rather than guessed at: a commit is not a version, and
    inventing one would put a number on the About page that no installed package agrees with.
    """
    out = []
    for token in (summary or "").split():
        if "==" not in token:
            continue
        name, _, version = token.partition("==")
        if name and version:
            out.append({"name": name, "version": version})
    return out


def _merge(declared, installed):
    """One row per library, carrying both answers so a disagreement is visible rather than resolved."""
    declared_names = [d["name"] for d in declared]
    extra = [i["name"] for i in installed if i["name"] not in set(declared_names)]
    want = {d["name"]: d["version"] for d in declared}
    got = {i["name"]: i["version"] for i in installed}
    return [
        {"name": name, "declared": want.get(name), "installed": got.get(name)}
        for name in declared_names + extra
    ]


def _installed_from_pip(docker_client, image, packages, logger):
    """Ask an unlabelled image what it has, by running pip inside it.

    Created **by image id, never by name**: `containers.run("silmused")` would pull a missing image,
    and no read-only endpoint should be able to make a grading host fetch anything. Everything else
    about this call is a bound, because this is the machine that runs student code — no network,
    capped memory, killed on timeout, removed in a finally.
    """
    if not packages:
        return []
    container = None
    try:
        container = docker_client.containers.create(
            image=image.id,
            command=["python3", "-m", "pip", "list", "--format=json", "--disable-pip-version-check"],
            network_disabled=True,
            mem_limit="256m",
        )
        container.start()
        container.wait(timeout=PIP_TIMEOUT_SEC)
        # stdout only: pip writes warnings to stderr, and mixing them in would corrupt the JSON.
        raw = container.logs(stdout=True, stderr=False).decode("utf-8", "replace")
        listed = {entry["name"].lower(): entry["version"] for entry in json.loads(raw)}
        return [{"name": name, "version": listed[name]} for name in packages if name in listed]
    except Exception as e:
        logger.info("could not read installed versions from {}: {}".format(image.id[:19], e))
        return []
    finally:
        if container is not None:
            try:
                container.remove(force=True)
            except Exception:
                # A stopped container left on a grading host is the sort of thing nobody notices for
                # a year, so this is worth attempting even when the run above failed.
                logger.info("could not remove the container used to inspect {}".format(image.id[:19]))


def grading_image_names():
    """The bare image names this host is expected to grade with.

    From the environment, set by the `easy-executor` unit out of the same `executor_images` list that
    decides what gets pulled, so the two cannot disagree. The default is what every environment has
    had for years, and is what a hand-built production host has too.

    Accepts commas or whitespace. The unit sends commas because systemd's `Environment=` splits an
    unquoted value on whitespace — which on dev quietly reduced a list of four names to one, and the
    About page showed a single grading image while every layer above it was correct. Accepting both
    means the same mistake cannot come back by a different route.
    """
    configured = os.environ.get("EASY_GRADING_IMAGE_NAMES", "")
    names = [n for n in re.split(r"[,\s]+", configured) if n]
    return names or list(DEFAULT_GRADING_IMAGE_NAMES)


def _live_grading_images(client):
    """The images grading actually uses, one per name — not every copy lying around.

    Two mistakes are possible here and this avoids both.

    **Reporting too much.** The reconciler deliberately keeps up to three superseded versions of each
    image so a rollback needs no network, and each of them still carries the labels. Selecting on
    "has a grading label" therefore reported about twelve images for four, with nothing to say which
    one grading used — and, because their names collided, duplicate React keys on the About page.

    **Reporting too little.** Selecting on labels alone also skipped production entirely, whose images
    were built by hand before any of this existed and carry none. Production is where version
    questions actually get asked, so that is the case worth getting right.

    So the rule is the one grading itself uses: `aae/containers.py` builds `FROM <bare name>`, so the
    image a bare tag resolves to *is* the image that grades. A retained rollback copy has only its
    `<registry>/<name>:i<digest>` tag and is correctly invisible.
    """
    wanted = set(grading_image_names())
    live = {}
    for image in client.images.list():
        for tag in image.tags or []:
            name = tag.split(":")[0]
            # A bare name — no registry, no slash. `ghcr.io/kspar/easy/silmused:i9f3…` is a retained
            # copy; `silmused:latest` is what grading resolves.
            if "/" in name or name not in wanted:
                continue
            live[name] = image
            break
        if len(live) >= IMAGE_INSPECT_LIMIT:
            break
    return live


def _refresh_grading_images(logger):
    """Rebuild the cache. Runs on a background thread, never on a request."""
    client = docker.from_env()
    images = []
    for name, image in _live_grading_images(client).items():
        labels = image.labels or {}
        declared = parse_versions(labels.get(LABEL_DECLARED))
        installed = parse_versions(labels.get(LABEL_INSTALLED))
        source = "label"
        if not installed:
            # No label: either a hand-built production image, or one built before EZ-1781. Ask pip,
            # which is the only way Docker offers. For a labelled image this never runs.
            asked = [d["name"] for d in declared] or [name]
            installed = _installed_from_pip(client, image, asked, logger)
            source = "pip" if installed else "unknown"

        images.append({
            "name": name,
            "created_at": (image.attrs or {}).get("Created"),
            "source": source,
            "inputs": labels.get(LABEL_INPUTS),
            "libraries": _merge(declared, installed),
        })
    return sorted(images, key=lambda i: i["name"])


def _read_cache_file():
    try:
        with open(IMAGE_CACHE_FILE, encoding="utf-8") as f:
            cached = json.load(f)
        if time() - cached.get("at", 0) < IMAGE_CACHE_TTL_SEC:
            return cached
    except (OSError, ValueError):
        pass
    return None


def _write_cache_file(path, payload):
    """Takes the path rather than reading the module global.

    The caller is a background thread that outlives the request which started it, so reading
    `IMAGE_CACHE_FILE` here would read whatever the attribute says *when the thread gets round to
    it*. That is a race in production only in theory — nothing rewrites it — but it is a real one in
    the test suite, where the value is patched per test: a thread finishing after its test had
    written to the machine-wide path, leaving a cache file that made a later run of the same suite
    fail. Capturing the path at spawn removes the question.
    """
    try:
        tmp = path + ".new"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(payload, f)
        os.replace(tmp, path)
    except OSError:
        # In-memory only then: one refresh per worker per hour instead of one per host. Worth
        # degrading to rather than failing, and worth saying can happen rather than assuming the file
        # always works.
        pass


def grading_images(logger):
    """What this host can grade with, and which library versions each image has.

    Never blocks, and never touches Docker on the calling thread. An empty list is a legitimate
    answer — a cold cache, a daemon that is down, an executor older than this code — and all of them
    mean the same thing to whoever is reading: we cannot say.
    """
    global _image_cache

    with _image_cache_lock:
        fresh = time() - _image_cache["at"] < IMAGE_CACHE_TTL_SEC
        current = list(_image_cache["images"])

    if fresh:
        return current

    from_file = _read_cache_file()
    if from_file is not None:
        with _image_cache_lock:
            _image_cache = {"at": from_file["at"], "images": from_file["images"]}
        return list(from_file["images"])

    # One refresh at a time, and the request does not wait for it.
    if not _refresh_running.is_set():
        _refresh_running.set()
        # Captured now, not read inside the thread. See _write_cache_file.
        cache_path = IMAGE_CACHE_FILE

        def run():
            global _image_cache
            try:
                images = _refresh_grading_images(logger)
                payload = {"at": time(), "images": images}
                with _image_cache_lock:
                    _image_cache = payload
                _write_cache_file(cache_path, payload)
            except Exception as e:
                logger.info("could not list grading images: {}".format(e))
            finally:
                _refresh_running.clear()

        threading.Thread(target=run, daemon=True, name="grading-images").start()

    return current
