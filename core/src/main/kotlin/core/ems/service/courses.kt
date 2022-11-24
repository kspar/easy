package core.ems.service

import core.db.*
import core.ems.service.cache.CachingService
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import java.io.Serializable

// TODO: remove
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

fun assertExerciseIsAutoGradable(exerciseId: Long) {
    val autoGradable = transaction {
        (Exercise innerJoin ExerciseVer).slice(ExerciseVer.graderType)
            .select { Exercise.id eq exerciseId and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.graderType] }.single() == GraderType.AUTO
    }
    if (!autoGradable) throw InvalidRequestException(
        "Exercise $exerciseId is not automatically assessable",
        ReqError.EXERCISE_NOT_AUTOASSESSABLE
    )
}

data class Grade(val submissionId: String,
                 val studentId: String,
                 val grade: Int?,
                 val graderType: GraderType?,
                 val feedback: String?) : Serializable


@Service
class CourseService(val cachingService: CachingService) {

    /**
     * Return all valid grades for a course exercise. If a student has not submission to this exercise, their grade
     * is not contained in the list. Uses a cache.
     */
    fun selectLatestValidGrades(courseExerciseId: Long): List<Grade> {
        return cachingService.selectLatestValidGradesAll(courseExerciseId)
    }

    /**
     * Return valid grades for a course exercise for the given students.
     * If a student has not submission to this exercise, their grade is not contained in the list. Uses a cache.
     */
    fun selectLatestValidGrades(courseExerciseId: Long, studentIds: List<String>): List<Grade> {
        return cachingService.selectLatestValidGradesAll(courseExerciseId).filter { studentIds.contains(it.studentId) }
    }

    /**
     * Return a query for all students on the course, filtering by search query words and group IDs.
     */
    fun selectStudentsOnCourseQuery(courseId: Long, queryWords: List<String>,
                                    groups: List<Long>, includeUngrouped: Boolean): Query {
        val query = (Account innerJoin Student innerJoin StudentCourseAccess leftJoin StudentCourseGroup)
                .slice(Student.id,
                        Account.email,
                        Account.givenName,
                        Account.familyName)
                .select { StudentCourseAccess.course eq courseId }
                .withDistinct()

        if (groups.isNotEmpty()) {
            query.andWhere {
                if (includeUngrouped)
                    StudentCourseGroup.courseGroup inList groups or StudentCourseGroup.courseGroup.isNull()
                else
                    StudentCourseGroup.courseGroup inList groups
            }
        }

        queryWords.map(String::lowercase).forEach {
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
