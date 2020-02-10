package core.ems.service

import core.db.*
import core.exception.InvalidRequestException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.io.Serializable


fun assertCourseExists(courseId: Long) {
    if (!courseExists(courseId)) {
        throw InvalidRequestException("Course $courseId does not exist")
    }
}

fun courseExists(courseId: Long): Boolean {
    return transaction {
        Course.select { Course.id eq courseId }
                .count() > 0
    }
}

data class Grade(val submissionId: String,
                 val studentId: String,
                 val grade: Int?,
                 val graderType: GraderType?,
                 val feedback: String?) : Serializable

// Two almost identical services. See below for more.
@Service
class CourseService(val courseServiceCache: CourseServiceCache) {

    fun selectLatestValidGrades(courseExerciseId: Long, studentIds: List<String>): List<Grade> {
        return courseServiceCache.selectLatestValidGradesAll(courseExerciseId).filter { studentIds.contains(it.studentId) }
    }

    fun selectStudentsOnCourseQuery(courseId: Long, queryWords: List<String>, restrictedGroups: List<Long>): Query {
        val query = (Account innerJoin Student innerJoin StudentCourseAccess leftJoin StudentGroupAccess)
                .slice(Student.id,
                        Account.email,
                        Account.givenName,
                        Account.familyName)
                .select { StudentCourseAccess.course eq courseId }
                .withDistinct()

        if (restrictedGroups.isNotEmpty()) {
            query.andWhere {
                StudentGroupAccess.group inList restrictedGroups or
                        (StudentGroupAccess.group.isNull())
            }
        }

        queryWords.forEach {
            query.andWhere {
                (Student.id like "%$it%") or
                        (Account.email.lowerCase() like "%$it%") or
                        (Account.givenName.lowerCase() like "%$it%") or
                        (Account.familyName.lowerCase() like "%$it%")
            }
        }

        return query
    }
}

// Catchable peculiarity: https://stackoverflow.com/questions/12115996/spring-cache-cacheable-method-ignored-when-called-from-within-the-same-class
@Service
class CourseServiceCache {

    @Cacheable("selectLatestValidGrades")
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
}