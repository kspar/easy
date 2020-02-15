package core.ems.service

import core.db.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.io.Serializable

/**
 * Has to be a separate Spring component. Do not use directly - only through other Services.
 */
@Service
class PrivateCachingService {

    @Cacheable("selectLatestValidGrades")
    @Suppress("SENSELESS_COMPARISON")
    fun selectLatestValidGradesAll(courseExerciseId: Long): List<Grade> {
        return transaction {
            (Submission leftJoin TeacherAssessment leftJoin AutomaticAssessment)
                    .slice(Submission.id,
                            Submission.student,
                            TeacherAssessment.id,
                            TeacherAssessment.grade,
                            TeacherAssessment.feedback,
                            AutomaticAssessment.id,
                            AutomaticAssessment.grade,
                            AutomaticAssessment.feedback)
                    .select { Submission.courseExercise eq courseExerciseId }
                    .orderBy(Submission.createdAt, SortOrder.DESC)
                    .distinctBy { it[Submission.student] }
                    .map {
                        when {
                            it[TeacherAssessment.id] != null -> Grade(
                                    it[Submission.id].value.toString(),
                                    it[Submission.student].value,
                                    it[TeacherAssessment.grade],
                                    GraderType.TEACHER,
                                    it[TeacherAssessment.feedback])
                            it[AutomaticAssessment.id] != null -> Grade(
                                    it[Submission.id].value.toString(),
                                    it[Submission.student].value,
                                    it[AutomaticAssessment.grade],
                                    GraderType.AUTO,
                                    it[AutomaticAssessment.feedback])
                            else -> Grade(
                                    it[Submission.id].value.toString(),
                                    it[Submission.student].value,
                                    null,
                                    null,
                                    null)
                        }
                    }
        }
    }

    data class Acc(val id: String, val email: String, val moodleUsername: String?, val givenName: String, val familyName: String) : Serializable

    @Cacheable(value = ["account"], unless = "#result == null")
    fun selectAccount(username: String): Acc? {
        return transaction {
            Account.select { Account.id eq username }
                    .map {
                        Acc(
                                it[Account.id].value,
                                it[Account.email],
                                it[Account.moodleUsername],
                                it[Account.givenName],
                                it[Account.familyName]
                        )
                    }.singleOrNull()
        }
    }
}