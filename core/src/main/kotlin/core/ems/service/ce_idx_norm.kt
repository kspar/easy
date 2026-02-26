package core.ems.service

import core.db.Course
import core.db.CourseExercise
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update


private val log = KotlinLogging.logger {}

const val IDX_STEP = 1048576  // 2^20


fun normaliseAllCourseExIndices() {
    log.debug { "Normalising all course exercise indices" }

    val courses = transaction { Course.select(Course.id).map { it[Course.id].value } }

    courses.forEach {
        normaliseCourseExIndices(it)
    }
}


fun normaliseCourseExIndices(courseId: Long) {
    data class OrderedEx(val id: Long, val idx: Int)

    log.debug { "Normalising course exercise indices for course $courseId" }

    val orderedExercises = transaction {
        CourseExercise
            .select(CourseExercise.id, CourseExercise.orderIdx)
            .where { CourseExercise.course eq courseId }
            .orderBy(CourseExercise.orderIdx, SortOrder.ASC)
            .map {
                OrderedEx(
                    it[CourseExercise.id].value,
                    it[CourseExercise.orderIdx]
                )
            }
    }

    orderedExercises.forEachIndexed { i, ex ->
        log.trace { "${i + 1}. exercise ${ex.id} has index ${ex.idx}" }

        val expectedIdx = (i + 1) * IDX_STEP
        if (ex.idx != expectedIdx) {
            log.debug { "Update idx ${ex.idx} -> $expectedIdx" }
            updateCourseExIdx(ex.id, expectedIdx)
        }
    }
}


private fun updateCourseExIdx(id: Long, newIdx: Int) {
    transaction {
        CourseExercise.update({ CourseExercise.id eq id }) {
            it[orderIdx] = newIdx
        }
    }
}
