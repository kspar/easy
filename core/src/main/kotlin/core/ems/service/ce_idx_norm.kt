package core.ems.service

import core.db.Course
import core.db.CourseExercise
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update


private val log = KotlinLogging.logger {}

const val IDX_STEP = 1048576  // 2^20


fun normaliseAllCourseExIndices() {
    log.debug { "Normalising all course exercise indices" }

    val courses = transaction {
        Course.slice(Course.id).selectAll()
                .map { it[Course.id].value }
    }

    courses.forEach {
        normaliseCourseExIndices(it)
    }
}


fun normaliseCourseExIndices(courseId: Long) {
    data class OrderedEx(val id: Long, val idx: Int)

    log.debug { "Normalising course exercise indices for course $courseId" }

    val orderedExercises = transaction {
        CourseExercise
                .slice(CourseExercise.id, CourseExercise.orderIdx)
                .select { CourseExercise.course eq courseId }
                .orderBy(CourseExercise.orderIdx, SortOrder.ASC)
                .map {
                    OrderedEx(it[CourseExercise.id].value,
                            it[CourseExercise.orderIdx])
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
