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