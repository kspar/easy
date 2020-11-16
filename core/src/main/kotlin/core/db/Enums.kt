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

// Length in Table object is 10
// Stronger permissions must be defined after weaker ones - definition order specifies natural comparison order
enum class DirAccessLevel {
    // TODO: add P
    R,
    RA, // Only for non-implicit dirs
    RAW,
    RAWM
}
