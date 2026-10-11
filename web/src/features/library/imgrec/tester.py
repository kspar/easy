import os

from grader import *

from tester_2 import judge

# The two lines a teacher changes per exercise. The rubric can be Estonian or English.
RUBRIC = "Pildil peaks olema maja."
THRESHOLD = 70  # percent


@test
@set_description('Pildituvastus')
def do_test(m):
    result = judge("screenshot.jpg", RUBRIC)
    score = result["score"]

    with open(os.path.join(os.environ['HOME'], "testResult.txt"), "w") as f:
        f.write("\nTulemus: " + str(score) + "\n")
        f.write("================\n")
        f.write("Nähtud: " + result["seen"] + "\n")
        f.write("Värvid: " + ", ".join(result["colors"]) + "\n")
        f.write(result["feedback_et"] + "\n")

    assert score >= THRESHOLD, (
        "Kahjuks ei suutnud pildituvastamise automaatkontroll Teie esitatud lahendusest \n"
        "piisavalt selgelt pilti välja lugeda. Teie pildituvastuse skoor oli " + str(score) + "%, kuid \n"
        "minimaalne skoor automaatseks arvestuseks on " + str(THRESHOLD) + "%.\n\n"
        + result["feedback_et"])
