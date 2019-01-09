/** Page-specific functions **/

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

function paintStudentExercises(exercises, courseId, courseTitle) {
    exercises.forEach((e) => {
        console.debug("Exercise " + e.id + ", title: " + e.title + ", deadline: " + e.deadline +
            ", status: " + e.status + ", grade: " + e.grade + ", graded_by: " + e.graded_by);

        const statusItem = $("<div></div>").addClass("col").addClass("s2"); // TODO: icon

        const titleItem = $("<div></div>").addClass("col").addClass("s4").text(e.title);

        const deadlineString = "Tähtaeg: " + e.deadline;  // TODO: format, empty if null
        const deadlineItem = $("<div></div>").addClass("col").addClass("s4").text(deadlineString);

        const gradeString = (e.grade === null ? "--" : e.grade) + "/100";
        const gradeItem = $("<div></div>").addClass("col").addClass("s2").text(gradeString); // TODO: graded_by icon

        const exerciseItem = $("<a></a>").addClass("row").addClass("collection-item").addClass("student-exercise-item")
            .attr("href", "/exercise.html?" + "course-id=" + courseId + "&course-title=" + courseTitle +
                "&exercise-id=" + e.id + "&exercise-title=" + e.title)
            .append(statusItem, titleItem, deadlineItem, gradeItem);

        $("#exercises-list").append(exerciseItem);
    });
}

function paintTeacherExercises(exercises, courseId, courseTitle) {
    exercises.forEach((e) => {
        console.debug("Exercise " + e.id + ", title: " + e.title + ", soft_deadline: " + e.soft_deadline +
            ", grader_type: " + e.grader_type + ", unstarted_count: " + e.unstarted_count + ", graded_count: " + e.graded_count +
            ", ungraded_count: " + e.ungraded_count + ", started_count: " + e.started_count + ", completed_count: " + e.completed_count);

        const deadlineString = "Tähtaeg: " + e.soft_deadline;  // TODO: format, empty if null
        const count1String = "Esitamata: " + e.unstarted_count;
        const count2String = e.grader_type === "AUTO" ? "Alustanud: " + e.started_count : "Hindamata: " + e.ungraded_count;
        const count3String = e.grader_type === "AUTO" ? "Lõpetanud: " + e.completed_count : "Hinnatud: " + e.graded_count;

        const exerciseItem = $("#teacher-exercise-item").clone()
            .removeAttr("id").removeAttr("style")
            .attr("href", "/exercise.html?" + "course-id=" + courseId + "&course-title=" + courseTitle +
                "&exercise-id=" + e.id + "&exercise-title=" + e.title);

        exerciseItem.find(".title-wrap").text(e.title);
        exerciseItem.find(".deadline-wrap").text(deadlineString);
        exerciseItem.find(".unstarted-wrap").text(count1String);
        exerciseItem.find(".started-wrap").text(count2String);
        exerciseItem.find(".completed-wrap").text(count3String);

        // TODO: show grader_type somehow

        $("#exercises-list").append(exerciseItem);
    });
}

function paintStudentExerciseDetails(ex) {
    console.debug("Exercise title: " + ex.title + ", text: " + ex.text_html + ", deadline: " + ex.deadline +
        ", grader_type: " + ex.grader_type + ", threshold: " + ex.threshold);

    $("#exercise-title").text(ex.title);
    if (ex.text_html !== null) {
        $("#exercise-text").text(ex.text_html);
    }

    const deadlineString = ex.deadline; // TODO: format and null
    $("#exercise-soft-deadline").removeAttr("style")
        .find("#exercise-soft-deadline-value").text(deadlineString);

    const graderString = ex.grader_type === "AUTO" ? "automaatne" : "käsitsi";
    $("#exercise-grader").removeAttr("style")
        .find("#exercise-grader-value").text(graderString);

    $("#exercise-threshold").removeAttr("style")
        .find("#exercise-threshold-value").text(ex.threshold);
}

function paintStudentSubmit(s) {
    console.debug("Solution " + s.solution + ", time: " + s.submission_time + ", autograde_status: " + s.autograde_status +
        ", grade_auto: " + s.grade_auto + ", feedback_auto: " + s.feedback_auto + ", grade_teacher: " + s.grade_teacher +
        ", feedback_teacher: " + s.feedback_teacher);

    // TODO: paint submit tab
    // Init editor

    // var answer_editor = CodeMirror.fromTextArea(answerform, {
    //     mode: "javascript",
    //     lineNumbers: true,
    //     autoRefresh: true
    // });
    //
    // $('.collapsible').collapsible({
    //     accordion: false
    // });
}

function paintStudentSubmissions(submissions) {
    submissions.forEach((s) => {
        console.debug("Solution " + s.solution + ", time: " + s.submission_time + ", autograde_status: " + s.autograde_status +
            ", grade_auto: " + s.grade_auto + ", feedback_auto: " + s.feedback_auto + ", grade_teacher: " + s.grade_teacher +
            ", feedback_teacher: " + s.feedback_teacher);

        // TODO: paint submissions
        // No editors first, maybe later
    });
}

function paintTeacherSubmissions(submissions) {
    submissions.forEach((s) => {
        console.debug("Given_name " + s.given_name + ", family_name: " + s.family_name + ", submission_time: " + s.submission_time +
            ", grade: " + s.grade + ", graded_by: " + s.graded_by);

        const submissionItem = $("#teacher-submission-item").clone().removeAttr("id").removeAttr("style");

        const studentName = s.given_name + " " + s.family_name;
        submissionItem.find(".teacher-submission-student").text(studentName);

        const submissionTime = s.submission_time; // TODO: format
        submissionItem.find(".teacher-submission-time").text(submissionTime);

        const gradeString = (s.grade === null ? "--" : s.grade) + "/100";
        submissionItem.find(".teacher-submission-grade").text(gradeString);

        // TODO: show graded_by somehow

        $("#teacher-submissions-list").append(submissionItem);
    });
}

function paintTeacherExerciseDetails(ex) {
    console.debug("Exercise title: " + ex.title + ", text: " + ex.text_html + ", soft_deadline: " + ex.soft_deadline
        + ", hard_deadline: " + ex.hard_deadline + ", grader_type: " + ex.grader_type + ", threshold: " + ex.threshold +
        ", last_modified: " + ex.last_modified + ", student_visible: " + ex.student_visible +
        ", assessments_student_visible: " + ex.assessments_student_visible);

    $("#exercise-title").text(ex.title);
    $("#exercise-text").text(ex.text_html);

    const softDeadlineString = ex.soft_deadline; // TODO: format and null
    $("#exercise-soft-deadline").removeAttr("style")
        .find("#exercise-soft-deadline-value").text(softDeadlineString);

    const hardDeadlineString = ex.hard_deadline; // TODO: format and null
    $("#exercise-hard-deadline").removeAttr("style")
        .find("#exercise-hard-deadline-value").text(hardDeadlineString);

    const graderString = ex.grader_type === "AUTO" ? "automaatne" : "käsitsi";
    $("#exercise-grader").removeAttr("style")
        .find("#exercise-grader-value").text(graderString);

    $("#exercise-threshold").removeAttr("style")
        .find("#exercise-threshold-value").text(ex.threshold);

    const lastModifiedString = ex.last_modified; // TODO: format and null
    $("#exercise-last-modified").removeAttr("style")
        .find("#exercise-last-modified-value").text(lastModifiedString);

    const visibleString = ex.student_visible === true ? "Jah" : "Ei";
    $("#exercise-student-visible").removeAttr("style")
        .find("#exercise-student-visible-value").text(visibleString);

    const assessmentsVisibleString = ex.assessments_student_visible === true ? "Jah" : "Ei";
    $("#exercise-assessments-student-visible").removeAttr("style")
        .find("#exercise-assessments-student-visible-value").text(assessmentsVisibleString);
}


function createCodeEditor(textAreaId) {
    return CodeMirror.fromTextArea(textAreaId, {
        mode: "javascript",
        lineNumbers: true,
        readOnly: true,
        autoRefresh: true
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
    const courseName = getCourseTitleFromQuery();
    $("#course-crumb").text(courseName);
    document.title = courseName;
}

function initExercisePageNoAuth() {
    console.debug("Exercise page");

    // Set course and exercise breadcrumbs (exercise name will be later updated from service)
    const courseId = getCourseIdFromQueryOrNull();
    const exerciseId = getExerciseIdFromQueryOrNull();
    if (courseId === null || exerciseId === null) {
        return;
    }
    const courseName = getCourseTitleFromQuery();
    const exerciseName = getExerciseTitleFromQuery();
    $("#course-crumb").attr("href", "/exercises.html?course-id=" + courseId + "&course-title=" + courseName).text(courseName);
    $("#exercise-crumb").text(exerciseName);

    // Init default tabs
    $("#tabs").tabs();
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
    const courseId = getCourseIdFromQueryOrNull();
    const courseTitle = getCourseTitleFromQuery();

    console.debug("Course: " + courseId);
    if (courseId === null) {
        return;
    }

    await ensureTokenValid();

    if (isStudent()) {
        const exercises = await $.get({
            url: EMS_ROOT + "/student/courses/" + courseId + "/exercises",
            headers: getAuthHeader()
        });
        paintStudentExercises(exercises, courseId, courseTitle);

    } else if (isTeacher()) {
        const exercises = await $.get({
            url: EMS_ROOT + "/teacher/courses/" + courseId + "/exercises",
            headers: getAuthHeader()
        });
        paintTeacherExercises(exercises, courseId, courseTitle);

    } else {
        error("Roles missing or unhandled role", roles);
    }
}

async function initExercisePageAuth() {
    console.debug("Exercise page");

    const courseId = getCourseIdFromQueryOrNull();
    const exerciseId = getExerciseIdFromQueryOrNull();
    if (courseId === null || exerciseId === null) {
        return;
    }

    // Async get and paint all tabs
    if (isStudent()) {
        $("#tab-submit").removeAttr("style");
        $("#tab-my-submissions").removeAttr("style");
        initStudentExerciseDetailsTab(courseId, exerciseId);
        initStudentSubmitTab(courseId, exerciseId);
        initStudentSubmissionsTab(courseId, exerciseId);

    } else if (isTeacher()) {
        $("#tab-student-submissions").removeAttr("style");
        initTeacherExerciseDetailsTab(courseId, exerciseId);
        initTeacherSubmissionsTab(courseId, exerciseId);

    } else {
        error("Roles missing or unhandled role", roles);
    }

    // Init again with new tabs
    $("#tabs").tabs();


    //// if teacher (same)

    // attach listeners to open new tab on student and load submission

    // attach listeners to add assessment

}

async function initTeacherSubmissionsTab(courseId, exerciseId) {
    await ensureTokenValid();
    const submissions = await $.get({
        url: EMS_ROOT + "/teacher/courses/" + courseId + "/exercises/" + exerciseId + "/submissions/latest/students",
        headers: getAuthHeader()
    });

    paintTeacherSubmissions(submissions);
}

async function initTeacherExerciseDetailsTab(courseId, exerciseId) {
    await ensureTokenValid();
    const exercise = await $.get({
        url: EMS_ROOT + "/teacher/courses/" + courseId + "/exercises/" + exerciseId,
        headers: getAuthHeader()
    });

    paintTeacherExerciseDetails(exercise);
}

async function initStudentExerciseDetailsTab(courseId, exerciseId) {
    await ensureTokenValid();
    const exercise = await $.get({
        url: EMS_ROOT + "/student/courses/" + courseId + "/exercises/" + exerciseId,
        headers: getAuthHeader()
    });

    paintStudentExerciseDetails(exercise);
}

async function initStudentSubmitTab(courseId, exerciseId) {
    await ensureTokenValid();
    const submission = await $.get({
        url: EMS_ROOT + "/student/courses/" + courseId + "/exercises/" + exerciseId + "/submissions/latest",
        headers: getAuthHeader()
    });

    paintStudentSubmit(submission);
}

async function initStudentSubmissionsTab(courseId, exerciseId) {
    await ensureTokenValid();
    const submissions = await $.get({
        url: EMS_ROOT + "/student/courses/" + courseId + "/exercises/" + exerciseId + "/submissions",
        headers: getAuthHeader()
    });

    paintStudentSubmissions(submissions);
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

function getCourseIdFromQueryOrNull() {
    const courseId = getQueryParam("course-id");
    if (courseId === null || courseId === undefined) {
        // TODO: show error message
        error("No course id found", window.location.href);
        return null;
    }
    return courseId;
}

function getExerciseIdFromQueryOrNull() {
    const exerciseId = getQueryParam("exercise-id");
    if (exerciseId === null || exerciseId === undefined) {
        // TODO: show error message
        error("No exercise id found", window.location.href);
        return null;
    }
    return exerciseId;
}

function getCourseTitleFromQuery() {
    let courseName = getQueryParam("course-title");
    if (courseName === null || courseName === undefined) {
        error("Course title not found", window.location.href);
        courseName = "Ülesanded";
    }
    return courseName;
}

function getExerciseTitleFromQuery() {
    let exerciseName = getQueryParam("exercise-title");
    if (exerciseName === null || exerciseName === undefined) {
        error("Exercise title not found", window.location.href);
        exerciseName = "Ülesanne";
    }
    return exerciseName;
}

function isStudent() {
    return !FORCE_TEACHER && hasRole("student");
}

function isTeacher() {
    return FORCE_TEACHER || hasRole("teacher");
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
const FORCE_TEACHER = true; // Assumes the user has teacher role obviously

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

