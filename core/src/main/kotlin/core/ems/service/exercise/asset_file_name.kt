package core.ems.service.exercise

/**
 * What an auto-assessed exercise's asset may be called.
 *
 * An asset is a file the exercise supplies next to the student's submission, and the name travels
 * from the request body through `Asset.fileName` and `executor_utils.kt` to the executor, which joins
 * it onto a directory and opens it. `os.path.join` treats `..` as "up" and discards its base entirely
 * in front of an absolute path, so a name that is not a plain file name writes somewhere other than
 * where the exercise meant — and the directory one level up is the Docker build context the grading
 * image is built from.
 *
 * The executor enforces this too, in `_checked_asset_name`, and that is the enforcement that matters:
 * it is the code that opens the file, and core is not the only thing that could ever call it. This
 * pattern exists so the teacher who typed the name hears about it while saving the exercise, instead
 * of every submission to that exercise failing later for reasons visible only in an executor log.
 *
 * A constant shared by the create and update DTOs rather than a rule written out twice. The two
 * request bodies are otherwise independent by design, but a validation rule that exists in two places
 * is a rule that will eventually be tightened in one of them.
 *
 * Rejected: anything containing `/` or `\`, the names `.` and `..`, the empty string, and control
 * characters. Deliberately still allowed: a name that collides with `submission.py` or `lahendus.py`,
 * because an exercise supplying the program under test is a real thing that real exercises do.
 */
const val ASSET_FILE_NAME_PATTERN = """(?!\.\.?$)[^/\\\x00-\x1F]+"""

const val ASSET_FILE_NAME_MESSAGE =
    "must be a plain file name: no '/' or '\\', not '.' or '..', and no control characters"
