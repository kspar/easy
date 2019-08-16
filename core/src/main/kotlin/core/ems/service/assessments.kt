package core.ems.service

import core.db.AutomaticAssessment
import core.db.TeacherAssessment
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select


/**
 * Return the valid grade for this submission or null if it's ungraded.
 * This grade can come from either a teacher or automatic assessment.
 */
fun selectLatestGradeForSubmission(submissionId: Long): Int? {
    val teacherGrade = TeacherAssessment
            .slice(TeacherAssessment.submission,
                    TeacherAssessment.createdAt,
                    TeacherAssessment.grade)
            .select { TeacherAssessment.submission eq submissionId }
            .orderBy(TeacherAssessment.createdAt to SortOrder.DESC)
            .limit(1)
            .map { it[TeacherAssessment.grade] }
            .firstOrNull()

    if (teacherGrade != null)
        return teacherGrade

    val autoGrade = AutomaticAssessment
            .slice(AutomaticAssessment.submission,
                    AutomaticAssessment.createdAt,
                    AutomaticAssessment.grade)
            .select { AutomaticAssessment.submission eq submissionId }
            .orderBy(AutomaticAssessment.createdAt to SortOrder.DESC)
            .limit(1)
            .map { it[AutomaticAssessment.grade] }
            .firstOrNull()

    return autoGrade
}