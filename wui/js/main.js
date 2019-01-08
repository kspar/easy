function reportError(o1, o2) {
    // TODO: report error
}

function error(o1, o2) {
    console.error(o1);
    console.error(o2);
    reportError(o1, o2);
}

function ensureTokenValid() {
    if (AUTH_ENABLED) {
        return kc.updateToken(TOKEN_MIN_VALID_SEC)
            .error(() => {
                error("Token refresh failed");
            });

    } else {
        return new Promise.resolve();
    }
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
    if (AUTH_ENABLED) {
        authenticate();
    }
    // authenticate

    // parse auth token to retrieve user info

    // Init profile dropdown menu
    $(".dropdown-trigger").dropdown();

    // Init logout link to redirect back to current page
    const redirectUri = window.location.href;
    const logoutLink = "https://idp.lahendus.ut.ee/auth/realms/master/protocol/openid-connect/logout?redirect_uri=" + encodeURIComponent(redirectUri);
    $("#logout-link").attr("href", logoutLink);
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


const AUTH_ENABLED = false;

const TOKEN_MIN_VALID_SEC = 20;

// Keycloak object
let kc;

$(document).ready(() => {
    runPageCode();
});

