package core.ems.service.code.similarity

/** The language of an exercise's solutions, as similarity tells them apart; [wireName] is what the response says. */
enum class SimilarityLanguage(val wireName: String) {
    PYTHON("python"),
    SQL("sql"),

    /** Everything else. */
    TEXT("text");

    companion object {
        /**
         * The language of an exercise's solutions: SQL where Silmused grades it, else the solution file
         * name's extension.
         *
         * The grader first, because the file name can be wrong: every new exercise starts as `lahendus.py`,
         * so an SQL exercise graded by Silmused often keeps that name, and read by the name alone its
         * queries would be parsed as Python. [containerImage] is the image's name, with a tag or without.
         */
        fun of(solutionFileName: String, containerImage: String?): SimilarityLanguage {
            if (containerImage?.substringBefore(':') == "silmused") return SQL
            return when (solutionFileName.substringAfterLast('.', "").lowercase()) {
                "py" -> PYTHON
                "sql" -> SQL
                else -> TEXT
            }
        }
    }
}
