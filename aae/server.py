# coding=utf-8

import os
import subprocess
from datetime import datetime, timezone
import time
import json
import typing as T

from flask import Flask
from flask import jsonify
from flask import request
from werkzeug.exceptions import BadRequest

import containers
from containers import grade_submission, RunStatus

# TODO: move to conf file
TIME_EXCEEDED_MESSAGE = "Programmi kontrollimine ületas lubatud käivitusaega."
MEM_EXCEEDED_MESSAGE = "Programmi kontrollimine ületas lubatud mälumahtu."
SOMETHING_FAILED_MESSAGE = "Automaatkontrollimise käigus tekkis ootamatu viga. Tõenäoliselt on esitatud lahenduses midagi valesti."

app = Flask(__name__)
app.logger.setLevel("DEBUG")

# Version reporting (EZ-1709). Read once at import: the answer cannot change while the process
# runs, and /v1/version is called by core on a timer rather than by a person.
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _read_version() -> str:
    """The product version, from the repo-root VERSION file that core and web also read."""
    try:
        with open(os.path.join(_REPO_ROOT, "VERSION")) as f:
            return f.read().strip() or "unknown"
    except OSError:
        # A deployment that copied only aae/ has no VERSION file. Saying "unknown" is the useful
        # failure; refusing to start a grading service over a version string is not.
        return "unknown"


def _read_commit() -> str:
    """
    The commit this executor is running.

    Unlike core and web, aae has no build step to stamp anything into, so there are two answers and
    a fallback. A `COMMIT` file beside `VERSION` wins: that is what a deploy writes, and a deployed
    executor is a copy of the source with no git history to ask. Otherwise ask git, which is the
    answer while developing in a checkout. "unknown" where neither exists — a grading service must
    not fail to start over a diagnostic string.
    """
    try:
        with open(os.path.join(_REPO_ROOT, "COMMIT")) as f:
            stamped = f.read().strip()
            if stamped:
                return stamped
    except OSError:
        pass

    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short=7", "HEAD"],
            cwd=_REPO_ROOT, capture_output=True, text=True, timeout=5,
        )
        return out.stdout.strip() or "unknown" if out.returncode == 0 else "unknown"
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def _read_deployed_at() -> str:
    """
    When this executor's code was put here, as an ISO timestamp.

    There is no build to date — aae is copied, not compiled — so the honest equivalent is the
    modification time of the source itself, which a deploy sets when it writes the file. Answers
    "is this running what we shipped an hour ago", which is the question core and web answer with
    their build times.
    """
    try:
        return datetime.fromtimestamp(
            os.path.getmtime(os.path.abspath(__file__)), tz=timezone.utc
        ).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    except OSError:
        return ""


VERSION = _read_version()
COMMIT = _read_commit()
DEPLOYED_AT = _read_deployed_at()


def check_content(content):
    if set(content.keys()) != {"submission", "grading_script", "assets", "image_name", "max_time_sec", "max_mem_mb"}:
        raise BadRequest("Missing or incorrect parameter")

    if not isinstance(content["assets"], list):
        raise BadRequest("Assets must be list")

    for dic in content["assets"]:
        if set(dic.keys()) != {"file_name", "file_content"}:
            raise BadRequest("Missing or incorrect parameter")


def assets_to_tuples(assets):
    assets_list = []

    for asset in assets:
        assets_list.append((asset["file_name"], asset["file_content"]))

    return assets_list


def parse_v3(raw_output) -> T.Optional[T.Tuple[int, str]]:
    try:
        j: dict = json.loads(raw_output)
    except json.JSONDecodeError:
        return None

    if j.get('result_type', None) == 'OK_V3':
        points = int(j['points'])
        feedback = raw_output
        return points, feedback

    return None


def parse_assessment_output(raw_output) -> T.Tuple[int, str]:
    assessment_v3 = parse_v3(raw_output)
    if assessment_v3 is not None:
        return assessment_v3

    # TODO: pygrader and imgrec should produce OK_LEGACY json messages
    grade_separator = "#" * 50

    grade_string = raw_output.rstrip().split("\n")[-1].lower().strip()
    app.logger.debug("Grade string: " + grade_string)

    if not grade_string.startswith("grade:"):
        app.logger.error("'grade:' not found")
        raise Exception("Incorrect grader output format")

    grade_list = grade_string.split(":")
    if len(grade_list) != 2:
        app.logger.error("More : than expected, len(grade_list) = " + str(len(grade_list)))
        raise Exception("Incorrect grader output format")

    grade = grade_list[1].strip()

    if not grade.isnumeric():
        raise Exception("Grade is not a number")

    output_rsplit = raw_output.rsplit(grade_separator, 1)

    if len(output_rsplit) < 2:
        app.logger.error("Grade separator missing")
        raise Exception("Incorrect grader output format")

    return round(float(grade)), grade_separator.join(output_rsplit[0:-1])


@app.route('/v1/grade', methods=['POST'])
def post_grade():
    # app.logger.info("Request: " + request.get_data(as_text=True))
    request_time = time.time()
    app.logger.info("Request started: {}".format(request_time))

    if not request.is_json:
        raise BadRequest("Request body must be JSON")

    content = request.get_json()
    check_content(content)

    # TODO: dummy switch from conf

    status, raw_output = grade_submission(content["submission"], content["grading_script"],
                                          assets_to_tuples(content["assets"]), content["image_name"],
                                          content["max_time_sec"], content["max_mem_mb"], app.logger, request_time)

    if status == RunStatus.SUCCESS:
        try:
            assessment = parse_assessment_output(raw_output)
        except Exception as e:
            app.logger.error(e)
            assessment = (0, SOMETHING_FAILED_MESSAGE + "\n\n" + raw_output)
    elif status == RunStatus.TIME_EXCEEDED:
        assessment = (0, TIME_EXCEEDED_MESSAGE)
    elif status == RunStatus.MEM_EXCEEDED:
        assessment = (0, MEM_EXCEEDED_MESSAGE)
    else:
        raise Exception("Unhandled run status: " + status.name)

    # app.logger.info("Assessment: " + str(assessment))
    app.logger.info("Request finished: {}".format(request_time))

    return jsonify({"grade": assessment[0], "feedback": assessment[1]})


@app.route('/v1/version', methods=['GET'])
def get_version():
    """
    What this executor is running (EZ-1709), and which grading libraries it can grade with (EZ-1781).
    Core calls it and passes the answer on to the About page, since nothing in a browser can reach an
    executor directly.

    `grading_images` is added to this endpoint rather than given one of its own. A second endpoint
    would double the round trips core makes and double its exposure to the two-second timeout it
    allows for a page render, in exchange for a few hundred bytes. It is safe in both directions:
    core ignores fields it does not know, and a core that predates this simply does not read it.

    Answered from a cache that a background thread fills, so no Docker work ever happens on this
    request. An empty list means "we cannot say" — a cold cache, a daemon that is down, an image
    nobody can identify — and the About page renders all of those the same way, because to a reader
    they are the same statement.
    """
    return jsonify({
        "version": VERSION,
        "commit": COMMIT,
        "built_at": DEPLOYED_AT,
        "grading_images": containers.grading_images(app.logger),
    })


@app.errorhandler(BadRequest)
def handle_bad_request(e):
    return jsonify({"message": e.description}), 400


if __name__ == '__main__':
    app.run(host='127.0.0.1', port=5000)
