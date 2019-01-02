from flask import Flask
from flask import jsonify
from flask import request
from werkzeug.exceptions import BadRequest
#from containers import grade_submission

app = Flask(__name__)


def checkContent(content):
    if set(content.keys()) != {"submission", "grading_script", "assets", "image_name", "max_time_sec", "max_mem_mb"}:
        raise BadRequest("Missing or incorrect parameter")

    if not isinstance(content["assets"], list):
        raise BadRequest("Assets must be list")

    for dic in content["assets"]:
        if set(dic.keys()) != {"file_name", "file_content"}:
            raise BadRequest("Missing or incorrect parameter")


@app.route('/v1/grade', methods=['POST'])
def postJson():
    if not request.is_json:
        raise BadRequest("Request body must be JSON")

    content = checkContent(request.get_json())

    # TODO: map assets
    # TODO: log

    #grade_submission(content["submission"], content["grading_script"], content["assets"], content["image_name"], content["max_time_sec"], content["max_mem_mb"])

    return jsonify({"grade": 2, "feedback": "Jama"})


@app.errorhandler(BadRequest)
def handle_bad_request(e):
    return jsonify({"message": e.description}), 400


app.run(host='127.0.0.1', port=5000)
