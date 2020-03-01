package core.ems.service.cache

import core.db.*
import core.ems.service.Grade
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.io.Serializable

private val log = KotlinLogging.logger {}

/**
 * Has to be a separate Spring component. Do not use directly - only through other Services.
 */
@Service
class PrivateCachingService {

    @Cacheable("selectLatestValidGrades")
    @Suppress("SENSELESS_COMPARISON")
    fun selectLatestValidGradesAll(courseExerciseId: Long): List<Grade> {
        log.debug { "$courseExerciseId not in 'selectLatestValidGrades' cache. Executing select." }
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
        log.debug { "$username not in 'account' cache. Executing select." }
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


    @Cacheable(value = ["student"], unless = "#result == false")
    fun studentExists(studentUsername: String): Boolean {
        log.debug { "$studentUsername not in 'student' cache. Executing select." }
        return transaction {
            Student.select { Student.id eq studentUsername }.count() == 1
        }
    }

    @Cacheable(value = ["teacher"], unless = "#result == false")
    fun teacherExists(teacherUsername: String): Boolean {
        log.debug { "$teacherUsername not in 'teacher' cache. Executing select." }
        return transaction {
            Teacher.select { Teacher.id eq teacherUsername }.count() == 1
        }
    }

    @Cacheable(value = ["admin"], unless = "#result == false")
    fun adminExists(adminUsername: String): Boolean {
        log.debug { "$adminUsername not in 'admin' cache. Executing select." }
        return transaction {
            Admin.select { Admin.id eq adminUsername }.count() == 1
        }
    }
}