package core.db

// Length in Table object is 20
enum class GraderType {
    AUTO,
    TEACHER
}

// Length in Table object is 20
enum class AutoGradeStatus {
    NONE,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

enum class StudentExerciseStatus {
    UNSTARTED,
    UNGRADED,
    STARTED,
    COMPLETED
}

// Stronger permissions must be defined after weaker ones - definition order specifies natural comparison order
enum class DirAccessLevel {
    // Pass-through, non-inheriting Read
    P,

    // Read everything in this dir
    PR,

    // Add to this dir, only for explicit dirs
    PRA,

    // Modify everything in this dir
    PRAW,

    // Manage permissions of everything in this dir
    PRAWM
}


enum class PriorityLevel {
    AUTHENTICATED,
    ANONYMOUS
}

enum class SolutionFileType {
    TEXT_EDITOR,
    TEXT_UPLOAD,
}

// Length in Table object is 20. How far a bug report has got towards becoming a YouTrack issue.
// DISABLED is not a failure: it says forwarding was switched off when the report arrived, which is
// what lets a later re-enable tell a backlog worth retrying from rows nobody ever intended to send.
enum class BugReportForwardState {
    PENDING,
    SENT,
    FAILED,
    DISABLED
}

// There was an InlineCommentType { COMMENT, SUGGESTION } here for the length of one commit. EZ-1777
// made the inline-comment `type` field an enum because core accepted any string in it; the answer to
// "what should this validate?" turned out to be that the field carried nothing `suggested_code` did
// not, so it is gone instead, along with its column. See the note on TeacherInlineComment.