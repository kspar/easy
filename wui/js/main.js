$(document).ready(function() {

    $("#tabs").tabs();

    var answer_editor = CodeMirror.fromTextArea(answerform, {
		mode:  "javascript",
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
});

function editor(id) {
    CodeMirror.fromTextArea(id, {
        mode: "javascript",
        lineNumbers: true,
        readOnly: true,
        autoRefresh: true
    });
}
