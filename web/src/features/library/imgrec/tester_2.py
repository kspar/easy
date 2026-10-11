"""Image judgement for imgrec exercises. Drop-in replacement for the Clarifai version:
`color_model()` keeps its name; `judge()` replaces the general-model concept score.

One request to Claude per submission. Only the screenshot and the teacher's rubric are
sent, never the student's code. Raw HTTP via `requests` rather than the Anthropic SDK
because the grading image and the Moodle VPL jail have `requests` and nothing else, and
not having to rebuild either is the point of this file.

The key sits in the grading container next to student code and can be read from there,
exactly as the Clarifai key could. Create it in its own Console workspace with a monthly
spend limit and an alert, so a leaked key cannot run up a bill.
"""
import base64
import json
import mimetypes
import os

import requests

API_URL = "https://api.anthropic.com/v1/messages"
API_KEY = os.environ.get("ANTHROPIC_API_KEY", "PUT-THE-WORKSPACE-KEY-HERE")
MODEL = "claude-opus-5-5"

SYSTEM = (
    "You grade a screenshot of a drawing that a beginner programming student made with "
    "Python tkinter or turtle. You are given one rubric sentence describing what the picture "
    "should show. Judge only what is visible. Be lenient about artistic quality: simple shapes "
    "that clearly add up to the thing described deserve a high score. Give a score 0-100 for "
    "how clearly the picture shows what the rubric asks, the dominant colours as CSS colour "
    "names, and one or two sentences of friendly feedback in Estonian addressed to the student "
    "(use 'Teie'). Never mention that you are an AI."
)

SCHEMA = {
    "type": "object",
    "properties": {
        "score": {"type": "integer", "description": "0 to 100"},
        "seen": {"type": "string", "description": "What the picture shows, one sentence, English"},
        "colors": {"type": "array", "items": {"type": "string"}},
        "feedback_et": {"type": "string"},
    },
    "required": ["score", "seen", "colors", "feedback_et"],
    "additionalProperties": False,
}

_cache = {}


def judge(image_path, rubric):
    """Ask once per (image, rubric). Returns a dict with score, seen, colors, feedback_et,
    or score 0 and a note if the request was declined."""
    key = (image_path, rubric)
    if key in _cache:
        return _cache[key]

    with open(image_path, "rb") as f:
        raw = f.read()
    if not raw:
        # The student's program ran but drew nothing the capture could save.
        result = {"score": 0, "seen": "empty image", "colors": [],
                  "feedback_et": "Programm ei joonistanud midagi, mida automaatkontroll saaks hinnata."}
        _cache[key] = result
        return result
    data = base64.b64encode(raw).decode("ascii")
    media_type = mimetypes.guess_type(image_path)[0] or "image/jpeg"

    body = {
        "model": MODEL,
        "max_tokens": 1024,
        "fallbacks": "default",
        "output_config": {
            "effort": "medium",
            "format": {"type": "json_schema", "schema": SCHEMA},
        },
        "system": SYSTEM,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "image", "source": {"type": "base64", "media_type": media_type, "data": data}},
                {"type": "text", "text": "Rubric: " + rubric},
            ],
        }],
    }
    headers = {
        "x-api-key": API_KEY,
        "anthropic-version": "2023-06-01",
        "anthropic-beta": "server-side-fallback-2026-07-01",
        "content-type": "application/json",
    }
    response = requests.post(API_URL, headers=headers, json=body, timeout=60)
    if response.status_code != 200:
        raise RuntimeError("Claude API {}: {}".format(response.status_code, response.text[:1000]))
    msg = response.json()

    if msg.get("stop_reason") == "refusal":
        result = {"score": 0, "seen": "declined", "colors": [],
                  "feedback_et": "Automaatkontroll ei saanud pilti hinnata; töö vaadatakse käsitsi üle."}
    else:
        text = next(b["text"] for b in msg["content"] if b.get("type") == "text")
        result = json.loads(text)

    _cache[key] = result
    return result


def color_model(image_path="screenshot.jpg", rubric=None):
    """Kept for the old tester.py call sites: the dominant colours as a list of names."""
    return judge(image_path, rubric or "Describe the picture.")["colors"]


if __name__ == "__main__":
    import sys
    path = sys.argv[1] if len(sys.argv) > 1 else "screenshot.jpg"
    rubric = sys.argv[2] if len(sys.argv) > 2 else "Describe the picture."
    print(json.dumps(judge(path, rubric), indent=1, ensure_ascii=False))
