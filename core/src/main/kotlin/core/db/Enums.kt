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

/**
 * What a teacher's inline comment on a line of code is.
 *
 * Was a bare `String` on both the request and the response until EZ-1777, stored verbatim and
 * echoed back, while `web/src/api/types.ts` declared a closed union — so the client's type was a
 * promise core did not keep and a teacher could `POST` any value at all. Nothing rendered a wrong
 * one, because the UI branches on `suggested_code` rather than on this, which is why it was inert
 * rather than a visible bug. The trap was the first reader to write `switch (c.type)`.
 *
 * **Today this carries no information `suggested_code` does not.** `AnnotatedCodeEditor.tsx` derives
 * it from whether a suggestion body is present, on every save, and omits the body when it is empty —
 * so `SUGGESTION` with nothing suggested is not a state the product can reach, and a reader must not
 * write a branch for it. Dropping the column was option 3 on EZ-1777 and is still open; it was not
 * taken here because "no reader anywhere" needs checking against the embed and any client outside
 * this repo, which is a separate question from whether the field validates. Validating a redundant
 * discriminator is cheap; deleting one on an assumption is not.
 */
// Length in Table object is 20
enum class InlineCommentType {
    COMMENT,
    SUGGESTION,
}