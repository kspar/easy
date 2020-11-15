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
// TODO: Consider storing it as ordinal in DB to make it easily comparable
enum class DirAccessLevel {
    R,
    RA, // Only for non-implicit dirs
    RAW,
    RAWM
}
