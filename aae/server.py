from flask import Flask
from flask import jsonify
from flask import request
from werkzeug.exceptions import BadRequest

from containers import grade_submission, RunStatus

# TODO: messages
TIME_EXCEEDED_MESSAGE = "TIME-TODO"
MEM_EXCEEDED_MESSAGE = "MEM-TODO"

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


def parse_assessment_output(raw_output):
    # TODO: parse
    return 42, "forty-nine"


@app.route('/v1/grade', methods=['POST'])
def post_grade():
    app.logger.info("Request: " + request.get_data(as_text=True))
    if not request.is_json:
        raise BadRequest("Request body must be JSON")

    content = request.get_json()
    check_content(content)

    status, raw_output = grade_submission(content["submission"], content["grading_script"],
                                          assets_to_tuples(content["assets"]), content["image_name"],
                                          content["max_time_sec"], content["max_mem_mb"], app.logger)

    if status == RunStatus.SUCCESS:
        assessment = parse_assessment_output(raw_output)
    elif status == RunStatus.TIME_EXCEEDED:
        assessment = (0, TIME_EXCEEDED_MESSAGE)
    elif status == RunStatus.MEM_EXCEEDED:
        assessment = (0, MEM_EXCEEDED_MESSAGE)
    else:
        raise Exception("Unhandled run status: " + status.name)

    app.logger.info("Assessment: " + str(assessment))

    return jsonify({"grade": assessment[0], "feedback": assessment[1]})


@app.errorhandler(BadRequest)
def handle_bad_request(e):
    return jsonify({"message": e.description}), 400


app.run(host='127.0.0.1', port=5000)
