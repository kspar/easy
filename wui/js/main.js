/** Page-specific functions **/

function editor(id) {
    CodeMirror.fromTextArea(id, {
        mode: "javascript",
        lineNumbers: true,
        readOnly: true,
        autoRefresh: true
    });
}

function paintStudentCourses(courses) {
    courses.forEach((c) => {
        console.log(c.id);
        console.log(c.title);
        const courseItem = $("<a></a>").addClass("collection-item").addClass("course-item")
            .attr("href", "/exercises.html?course-id=" + c.id)
            .text(c.title);
       $("#courses-list").append(courseItem);
    });
}


/** Init page functions **/

function initExercisePageNoAuth() {
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

function initCoursesPageNoAuth() {
    console.debug("Courses page")

}

async function initCoursesPageAuth() {
    await ensureTokenValid();

    if (isStudent()) {

        const courses = await $.get({
            url: EMS_ROOT + "/student/courses",
            headers: getAuthHeader(),
        });

        paintStudentCourses(courses);

    } else if (isTeacher()) {

    } else {
        error("Roles missing or unhandled role", roles);
    }

}

function initCommonNoAuth() {
    // Init profile dropdown menu
    $(".dropdown-trigger").dropdown();

    // Init logout link to redirect back to current page
    const redirectUri = window.location.href;
    // TODO: can get from kc?
    const logoutLink = "https://idp.lahendus.ut.ee/auth/realms/master/protocol/openid-connect/logout?redirect_uri=" + encodeURIComponent(redirectUri);
    $("#logout-link").attr("href", logoutLink);
}

async function initCommonAuth() {
    const token = kc.tokenParsed;
    //console.log(token);

    // Set display name
    let displayName = token.given_name;

    if (displayName === undefined) {
        error("Given name undefined", token);
        displayName = "Kasutaja";
    }
    $("#profile-name").text(displayName);

    // Set roles
    roles = token.easy_role;

    // Register with :ems
    await ensureTokenValid();

    return $.post({
        url: EMS_ROOT + "/register",
        headers: getAuthHeader(),
    }).done(() => {
        console.debug("Registration successful");
    });
}


/** General functions **/

/**
 * Initialize elements that require authentication.
 */
async function initPageAuth() {
    await initCommonAuth();

    const pageId = $("body").data("pageid");
    console.debug("Page id: " + pageId);

    switch (pageId) {
        case "courses":
            initCoursesPageAuth();
            break;
        case "exercise":

            break;
    }
}

/**
 * Initialize elements that do not require authentication.
 */
function initPageNoAuth() {
    initCommonNoAuth();

    const pageId = $("body").data("pageid");
    console.debug("Page id: " + pageId);

    switch (pageId) {
        case "courses":
            initCoursesPageNoAuth();
            break;
        case "exercise":
            initExercisePageNoAuth();
            break;
    }
}

function isStudent() {
    return hasRole("student");
}

function isTeacher() {
    return hasRole("teacher");
}

function hasRole(role) {
    if (roles === undefined) {
        error("Roles is undefined", new Error().stack);
    }
    return $.inArray(role, roles) !== -1
}

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

function getAuthHeader() {
    if (kc.token === undefined) {
        error("Token is undefined", kc);
    }
    return {"Authorization": "Bearer " + kc.token};
}

function authenticate() {
    kc = Keycloak();
    kc.init({
        onLoad: 'login-required'

    }).success((authenticated) => {
        console.debug("Authenticated: " + authenticated);
        initPageAuth();

    }).error((e) => {
        error("Keycloak init failed", e);
        // TODO: show error message
    });

    kc.onTokenExpired = () => {
        kc.updateToken();
    };

    kc.onAuthRefreshSuccess = () => {
        initCommonAuth(); // TODO: initPageAuth() if we want to refresh page content with token refresh
    };
}


/** Main **/

const AUTH_ENABLED = true;
const TOKEN_MIN_VALID_SEC = 20;
const EMS_ROOT = "https://ems.lahendus.ut.ee/v1";

// Keycloak object
let kc;
// Roles list, do not use directly
let roles;

$(document).ready(() => {
    if (AUTH_ENABLED) {
        authenticate();
    }
    initPageNoAuth();
});

