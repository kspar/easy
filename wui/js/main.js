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
        console.debug("Course " + c.id + ", title: " + c.title);
        const courseItem = $("<a></a>").addClass("collection-item").addClass("course-item")
            .attr("href", "/exercises.html?course-id=" + c.id + "&course-title=" + c.title)
            .text(c.title);
        $("#courses-list").append(courseItem);
    });
}

function paintTeacherCourses(courses) {
    courses.forEach((c) => {
        console.debug("Course " + c.id + ", title: " + c.title + ", count: " + c.student_count);
        const courseItem = $("<a></a>").addClass("collection-item").addClass("course-item")
            .attr("href", "/exercises.html?course-id=" + c.id + "&course-title=" + c.title)
            .text(c.title);
        const studentCountString = c.student_count + (c.student_count === 1 ? " õpilane" : " õpilast");
        const studentCountItem = $("<span></span>").addClass("right").addClass("course-student-count")
            .text(studentCountString);
        courseItem.append(studentCountItem);
        $("#courses-list").append(courseItem);
    });
}

function paintStudentExercises(exercises) {
    exercises.forEach((e) => {
        console.debug("Exercise " + e.id + ", title: " + e.title + ", deadline: " + e.deadline +
            ", status: " + e.status + ", grade: " + e.grade + ", graded_by: " + e.graded_by);

        const statusItem = $("<div></div>").addClass("col").addClass("s2"); // TODO: icon

        const titleItem = $("<div></div>").addClass("col").addClass("s4").text(e.title);

        const deadlineString = "Tähtaeg: " + e.deadline;  // TODO: format
        const deadlineItem = $("<div></div>").addClass("col").addClass("s4").text(deadlineString);

        const gradeString = (e.grade === null ? "--" : e.grade) + "/100";
        const gradeItem = $("<div></div>").addClass("col").addClass("s2").text(gradeString); // TODO: graded_by icon

        const exerciseItem = $("<a></a>").addClass("row").addClass("collection-item").addClass("exercise-item")
            .attr("href", "/exercise.html?exercise-id=" + e.id + "&exercise-title=" + e.title)
            .append(statusItem, titleItem, deadlineItem, gradeItem);

        $("#exercises-list").append(exerciseItem);
    });
}


/** Init page before auth functions **/

function initCommonNoAuth() {
    // Init profile dropdown menu
    $(".dropdown-trigger").dropdown();

    // Init logout link to redirect back to current page
    const redirectUri = window.location.href;
    // TODO: can get from kc?
    const logoutLink = "https://idp.lahendus.ut.ee/auth/realms/master/protocol/openid-connect/logout?redirect_uri=" + encodeURIComponent(redirectUri);
    $("#logout-link").attr("href", logoutLink);
}

function initCoursesPageNoAuth() {
    console.debug("Courses page");
}

function initExercisesPageNoAuth() {
    console.debug("Exercises page");

    // Set breadcrumb name and page title to course name
    let courseName = getQueryParam("course-title");
    if (courseName === null || courseName === undefined) {
        error("Course title not found", window.location.href);
        courseName = "Ülesanded";
    }

    $("#course-crumb").text(courseName);
    document.title = courseName;
}

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


/** Init page after auth functions **/

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

async function initCoursesPageAuth() {
    await ensureTokenValid();

    if (isStudent()) {

        const courses = await $.get({
            url: EMS_ROOT + "/student/courses",
            headers: getAuthHeader(),
        });
        paintStudentCourses(courses);

    } else if (isTeacher()) {

        const courses = await $.get({
            url: EMS_ROOT + "/teacher/courses",
            headers: getAuthHeader(),
        });
        paintTeacherCourses(courses);

    } else {
        error("Roles missing or unhandled role", roles);
    }
}

async function initExercisesPageAuth() {

    // get course id
    const courseId = getQueryParam("course-id");
    console.debug("Course: " + courseId);
    if (courseId === null || courseId === undefined) {
        // TODO: show error message
        error("No course id found", window.location.href);
    }

    await ensureTokenValid();

    if (isStudent()) {
        const exercises = await $.get({
            url: EMS_ROOT + "/student/courses/" + courseId + "/exercises",
            headers: getAuthHeader()
        });
        paintStudentExercises(exercises);


    } else if (isTeacher()) {
        // TODO

    } else {
        error("Roles missing or unhandled role", roles);
    }

}

async function initExercisePageAuth() {
    console.debug("Exercise page");
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
        case "exercises":
            initExercisesPageAuth();
            break;
        case "exercise":
            initExercisePageAuth();
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
        case "exercises":
            initExercisesPageNoAuth();
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

function getQueryParam(key) {
    const params = new URLSearchParams(window.location.search);
    return params.get(key);
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

