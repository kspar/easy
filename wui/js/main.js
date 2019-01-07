function debug(msg) {
    console.debug(msg)
}


function editor(id) {
    CodeMirror.fromTextArea(id, {
        mode: "javascript",
        lineNumbers: true,
        readOnly: true,
        autoRefresh: true
    });
}

function exercisePage() {
    // Init tabs
    $("#tabs").tabs();

    var answer_editor = CodeMirror.fromTextArea(answerform, {
        mode: "javascript",
        lineNumbers: true,
        autoRefresh: true
    });

    $('.collapsible').collapsible({
        accordion: false
    });

    var answers = document.getElementsByClassName("answer-item");
    for (var i = 0; i < answers.length; i++) {
        editor(answers[i]);
    }
}



function coursesPage() {
    debug("Courses page")

}

function authenticate() {

}

function common() {

    // authenticate

    // parse auth token to retrieve user info
}



function runPageCode() {
    const pageId = $("body").data("pageid");
    debug("Page id: " + pageId);

    common();

    if (pageId === "courses") {
        coursesPage()

    } else if (pageId === "exercise") {
        exercisePage()
    }
}


$(document).ready(() => {
    runPageCode();
});

