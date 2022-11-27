# coding=utf-8

import time
import json
import typing as T

from flask import Flask
from flask import jsonify
from flask import request
from werkzeug.exceptions import BadRequest

from containers import grade_submission, RunStatus

# TODO: move to conf file
TIME_EXCEEDED_MESSAGE = "Programmi kontrollimine ületas lubatud käivitusaega."
MEM_EXCEEDED_MESSAGE = "Programmi kontrollimine ületas lubatud mälumahtu."
SOMETHING_FAILED_MESSAGE = "Automaatkontrollimise käigus tekkis ootamatu viga. Tõenäoliselt on esitatud lahenduses midagi valesti."

app = Flask(__name__)
app.logger.setLevel("DEBUG")


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


@app.errorhandler(BadRequest)
def handle_bad_request(e):
    return jsonify({"message": e.description}), 400


if __name__ == '__main__':
    app.run(host='127.0.0.1', port=5000)
