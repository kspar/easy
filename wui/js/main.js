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

        const deadlineString = (e.deadline ? "Tähtaeg: " + formatDateTime(e.deadline) : "");
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

        const deadlineString = (e.soft_deadline ? "Tähtaeg: " + formatDateTime(e.soft_deadline) : "");
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

    if (ex.deadline) {
        const deadlineString = formatDateTime(ex.deadline);
        $("#exercise-soft-deadline").removeAttr("style")
            .find("#exercise-soft-deadline-value").text(deadlineString);
    }

    const graderString = ex.grader_type === "AUTO" ? "automaatne" : "käsitsi";
    $("#exercise-grader").removeAttr("style")
        .find("#exercise-grader-value").text(graderString);

    $("#exercise-threshold").removeAttr("style")
        .find("#exercise-threshold-value").text(ex.threshold);
}

function paintStudentSubmit(s) {

    let previousSolution = "";

    if (s !== null && s !== undefined) {
        console.debug("Solution " + s.solution + ", time: " + s.submission_time + ", autograde_status: " + s.autograde_status +
            ", grade_auto: " + s.grade_auto + ", feedback_auto: " + s.feedback_auto + ", grade_teacher: " + s.grade_teacher +
            ", feedback_teacher: " + s.feedback_teacher);

        $("#submission-time").text(formatDateTime(s.submission_time));
        $("#submission-time-wrapper").show();

        if (s.grade_auto !== null) {
            $("#auto-grade").text(s.grade_auto);
            $("#auto-feedback").text(s.feedback_auto);
            $("#submission-auto").show();
        }
        if (s.grade_teacher !== null) {
            $("#teacher-grade").text(s.grade_teacher);
            $("#teacher-feedback").text(s.feedback_teacher);
            $("#submission-teacher").show();
        }

        if (s.autograde_status === "IN_PROGRESS") {
            // TODO: show loading, start polling

        } else if (s.autograde_status === "FAILED") {
            $("#auto-grade").text("--");
            $("#auto-feedback").text("Automaatne kontrollimine ebaõnnestus");
            $("#submission-auto").show();
        }

        previousSolution = s.solution;
    }

    let editor;

    const existingEditor = $(".CodeMirror");
    if (existingEditor.length === 1) {
        editor = existingEditor[0].CodeMirror;

    } else {
        $("#submission-wrapper").show();
        editor = CodeMirror.fromTextArea(
            document.getElementById("submission"), {
                mode: "python",
                lineNumbers: true,
                autoRefresh: true
            });
    }

    editor.setValue(previousSolution);

    const submitButton =$("#submit-button");

    submitButton.click(() => {
        $("#submit-button").text("Kontrollin...").attr("disabled", true);
        $("#auto-feedback").text("Kontrollin...");
        $("#auto-grade").text("...");
        studentSubmitHandler(editor);
    });

    submitButton.text("Esita").attr("disabled", false);

    $("#submit-button-wrapper").show();
}

async function studentSubmitHandler(editor) {
    console.debug("Submitting solution");

    const submissionText = editor.getValue();
    const courseId = getCourseIdFromQueryOrNull();
    const exerciseId = getExerciseIdFromQueryOrNull();
    if (courseId === null || exerciseId === null) {
        return;
    }

    // Submit
    await $.post({
        url: EMS_ROOT + "/student/courses/" + courseId + "/exercises/" + exerciseId + "/submissions",
        headers: getAuthHeader(),
        data: JSON.stringify({"solution": submissionText}),
        contentType:"application/json; charset=utf-8"
    });

    // Start polling autograde status
    pollAutogradeStatus(courseId, exerciseId);
}

async function pollAutogradeStatus(courseId, exerciseId) {
    const sleepStart = 500;
    const sleepStep = 250;
    let sleepCounter = 0;

    while(true) {
        console.debug("Fetching submission...");
        await ensureTokenValid();
        const submission = await $.get({
            url: EMS_ROOT + "/student/courses/" + courseId + "/exercises/" + exerciseId + "/submissions/latest",
            headers: getAuthHeader()
        });

        if (submission.autograde_status !== "IN_PROGRESS") {
            console.debug("Autoassess finished with status: " + submission.autograde_status);
            paintStudentSubmit(submission);
            break;
        } else {
            console.debug("Autoassess still in progress");
        }

        sleepCounter++;
        const sleepTime = sleepStart + sleepCounter * sleepStep;
        console.debug("Sleeping for " + sleepTime + " ms");
        await sleep(sleepTime);
    }
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function paintStudentSubmissions(submissions) {
    submissions.forEach((s) => {
        console.debug("Solution " + s.solution + ", time: " + s.submission_time + ", autograde_status: " + s.autograde_status +
            ", grade_auto: " + s.grade_auto + ", feedback_auto: " + s.feedback_auto + ", grade_teacher: " + s.grade_teacher +
            ", feedback_teacher: " + s.feedback_teacher);

        // No editors first, maybe later
    });
}

function paintTeacherSubmissions(submissions) {
    submissions.forEach((s) => {
        console.debug("Id: "+ s.student_id + ", given_name: " + s.given_name + ", family_name: " + s.family_name + ", submission_time: " + s.submission_time +
            ", grade: " + s.grade + ", graded_by: " + s.graded_by);

        const submissionItem = $("#teacher-submission-item").clone().removeAttr("id").removeAttr("style");

        const studentName = s.given_name + " " + s.family_name;
        submissionItem.find(".teacher-submission-student").text(studentName);

        const submissionTime = formatDateTime(s.submission_time);
        submissionItem.find(".teacher-submission-time").text(submissionTime);

        const gradeString = (s.grade === null ? "--" : s.grade) + "/100";
        submissionItem.find(".teacher-submission-grade").text(gradeString);

        // TODO: show graded_by somehow

        const shortStudentName = s.given_name + " " + s.family_name[0];

        submissionItem.click(() => {
            teacherOpenSubmissionTab(s.student_id, shortStudentName);
        });

        $("#teacher-submissions-list").append(submissionItem);
    });
}

function paintTeacherSubmission(s) {
    console.debug("Id: " + s.id + ", solution " + s.solution + ", time: " + s.created_at +
        ", grade_auto: " + s.grade_auto + ", feedback_auto: " + s.feedback_auto + ", grade_teacher: " + s.grade_teacher +
        ", feedback_teacher: " + s.feedback_teacher);

    // Hide/init elements in case they have been used before
    $("#teacher-submission-auto").hide();
    $("#teacher-submission-teacher").hide();
    $("#add-grade-wrapper").hide();
    $("#grade-button").attr("disabled", false);

    $("#teacher-submission-time").text(formatDateTime(s.created_at));
    $("#teacher-submission-time-wrapper").show();

    if (s.grade_auto !== null) {
        $("#teacher-auto-grade").text(s.grade_auto);
        $("#teacher-auto-feedback").text(s.feedback_auto);
        $("#teacher-submission-auto").show();
    }
    if (s.grade_teacher !== null) {
        $("#teacher-teacher-grade").text(s.grade_teacher);
        $("#teacher-teacher-feedback").text(s.feedback_teacher);
        $("#teacher-submission-teacher").show();
    }

    $("#grade-button").off().click(() => {
        $("#grade-button").attr("disabled", true);
        teacherAddAssessment(s.id);
    });

    $("#grade-link").off().show().click(() => {
        $('#add-grade-wrapper').show();
        $("#grade-link").hide();
    });

    $("#teacher-submission-wrapper").show();

    const existingEditor = $(".CodeMirror");
    if (existingEditor.length === 1) {
        existingEditor[0].CodeMirror.setValue(s.solution);

    } else {
        CodeMirror.fromTextArea(
            document.getElementById("teacher-submission-submission"),
            {
                mode: "python",
                lineNumbers: true,
                readOnly: "nocursor",
                autoRefresh: true
            }
        ).setValue(s.solution);
    }
}

async function teacherAddAssessment(submissionId) {
    const feedback = $("#feedback").val();
    const grade = $("#grade").val();

    console.debug("New assessment, submissionId: "+ submissionId  +", grade: " + grade + ", feedback: " + feedback);

    const courseId = getCourseIdFromQueryOrNull();
    const exerciseId = getExerciseIdFromQueryOrNull();
    if (courseId === null || exerciseId === null) {
        return;
    }

    await $.post({
        url: EMS_ROOT + "/teacher/courses/" + courseId + "/exercises/" + exerciseId + "/submissions/" + submissionId + "/assessments",
        headers: getAuthHeader(),
        data: JSON.stringify({"grade": parseInt(grade), "feedback": feedback}),
        contentType:"application/json; charset=utf-8"
    });

    // Requery & repaint
    initTeacherSubmissionTab($("#teacher-student-id").val());
}

function teacherOpenSubmissionTab(studentId, studentName) {
    console.debug("Opening new tab for student: " + studentId);

    const submissionTab = $("#tab-student-submission");
    submissionTab.find("a").text(studentName);

    // Load contents & paint async
    initTeacherSubmissionTab(studentId);

    // Show tab if it's hidden
    submissionTab.show();

    $("#tabs").tabs("select", "student-submission");
        //.tabs("updateTabIndicator");
}

async function initTeacherSubmissionTab(studentId) {
    const courseId = getCourseIdFromQueryOrNull();
    const exerciseId = getExerciseIdFromQueryOrNull();
    if (courseId === null || exerciseId === null) {
        return;
    }

    console.debug("Init teacher submission tab for student: " + studentId);
    $("#teacher-student-id").val(studentId);

    const submission = await $.get({
        url: EMS_ROOT + "/teacher/courses/" + courseId + "/exercises/" + exerciseId + "/submissions/latest/students/" + studentId,
        headers: getAuthHeader(),
    });
    paintTeacherSubmission(submission);
}

function paintTeacherExerciseDetails(ex) {
    console.debug("Exercise title: " + ex.title + ", text: " + ex.text_html + ", soft_deadline: " + ex.soft_deadline
        + ", hard_deadline: " + ex.hard_deadline + ", grader_type: " + ex.grader_type + ", threshold: " + ex.threshold +
        ", last_modified: " + ex.last_modified + ", student_visible: " + ex.student_visible +
        ", assessments_student_visible: " + ex.assessments_student_visible);

    $("#exercise-title").text(ex.title);
    $("#exercise-text").text(ex.text_html);

    if (ex.soft_deadline) {
        const softDeadlineString = formatDateTime(ex.soft_deadline);
        $("#exercise-soft-deadline").removeAttr("style")
            .find("#exercise-soft-deadline-value").text(softDeadlineString);
    }

    if (ex.hard_deadline) {
        const hardDeadlineString = formatDateTime(ex.hard_deadline);
        $("#exercise-hard-deadline").removeAttr("style")
            .find("#exercise-hard-deadline-value").text(hardDeadlineString);
    }

    const graderString = ex.grader_type === "AUTO" ? "automaatne" : "käsitsi";
    $("#exercise-grader").removeAttr("style")
        .find("#exercise-grader-value").text(graderString);

    $("#exercise-threshold").removeAttr("style")
        .find("#exercise-threshold-value").text(ex.threshold);

    if (ex.last_modified) {
        const lastModifiedString = formatDateTime(ex.last_modified);
        $("#exercise-last-modified").removeAttr("style")
            .find("#exercise-last-modified-value").text(lastModifiedString);
    }

    const visibleString = ex.student_visible === true ? "Jah" : "Ei";
    $("#exercise-student-visible").removeAttr("style")
        .find("#exercise-student-visible-value").text(visibleString);

    const assessmentsVisibleString = ex.assessments_student_visible === true ? "Jah" : "Ei";
    $("#exercise-assessments-student-visible").removeAttr("style")
        .find("#exercise-assessments-student-visible-value").text(assessmentsVisibleString);
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
        $("#tab-submit").show();
        $("#tab-my-submissions").show();
        initStudentExerciseDetailsTab(courseId, exerciseId);
        initStudentSubmitTab(courseId, exerciseId);
        // TODO: submissions tabs
        //initStudentSubmissionsTab(courseId, exerciseId);

    } else if (isTeacher()) {
        $("#tab-student-submissions").show();
        initTeacherExerciseDetailsTab(courseId, exerciseId);
        initTeacherSubmissionsTab(courseId, exerciseId);

    } else {
        error("Roles missing or unhandled role", roles);
    }

    // Init again with new tabs
    $("#tabs").tabs();
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

function formatDateTime(rawString) {
    const months = ["jaanuar", "veebruar", "märts", "aprill", "mai", "juuni",
        "juuli", "august", "september", "oktoober", "november", "detsember"];

    const dateTimeParts = rawString.trim().split("T");
    const date = dateTimeParts[0];
    const time = dateTimeParts[1];

    const dateParts = date.split("-");
    const dayRaw = dateParts[2];
    const monthNumber = dateParts[1];
    const year = dateParts[0];
    const day = parseInt(dayRaw).toString();  // Remove leading 0s
    const month = months[parseInt(monthNumber) - 1];

    const timeParts = time.split(":");
    const hour = ((parseInt(timeParts[0]) + 2) % 24).toString();  // Dirty UTC+2 conversion :)
    const minute = timeParts[1];

    return day + ". " + month + " " + year + ", " + hour + "." + minute;
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
const FORCE_TEACHER = false; // Assumes the user has teacher role obviously

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
