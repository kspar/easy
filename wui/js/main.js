function reportError(o1, o2) {
    // TODO: report error
}

function error(o1, o2) {
    console.error(o1);
    console.error(o2);
    reportError(o1, o2);
}

function ensureTokenValid() {
    return kc.updateToken(TOKEN_MIN_VALID_SEC)
        .error(() => {
           error("Token refresh failed");
        });
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
    console.debug("Courses page")

}

function authenticate() {
    kc = Keycloak();
    kc.init({
        onLoad: 'login-required'

    }).success((authenticated) => {
        console.debug("Authenticated: " + authenticated);

    }).error(function (e) {
        error("Keycloak init failed", e);

    });

    kc.onTokenExpired = () => {
        kc.updateToken();
    }
}

function common() {

    authenticate()
    // authenticate

    // parse auth token to retrieve user info
}


function runPageCode() {
    const pageId = $("body").data("pageid");
    console.debug("Page id: " + pageId);

    common();

    if (pageId === "courses") {
        coursesPage()

    } else if (pageId === "exercise") {
        exercisePage()
    }
}


const TOKEN_MIN_VALID_SEC = 20;

// Keycloak object
let kc;

$(document).ready(() => {
    runPageCode();
});

