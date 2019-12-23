package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseExercise
import core.ems.service.IDX_STEP
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.normaliseCourseExIndices
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherReorderExerciseController {

    data class Req(@JsonProperty("new_index", required = true) val newIndex: Int)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/exercises/{courseExerciseId}/reorder")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @PathVariable("courseExerciseId") courseExerciseIdStr: String,
                   @Valid @RequestBody dto: Req,
                   caller: EasyUser) {

        log.debug { "Reorder course exercise $courseExerciseIdStr to new index ${dto.newIndex} (caller: ${caller.id})" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdStr.idToLongOrInvalidReq()
        val newIdx = dto.newIndex

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        setNewCourseExIndex(courseId, courseExId, newIdx)
    }
}


private fun setNewCourseExIndex(courseId: Long, courseExId: Long, rawNewIdx: Int) {
    data class OrderedEx(val id: Long, val idx: Long)

    val orderedExercises = transaction {
        CourseExercise
                .slice(CourseExercise.id, CourseExercise.orderIdx)
                .select { CourseExercise.course eq courseId }
                .orderBy(CourseExercise.orderIdx, SortOrder.ASC)
                .map {
                    OrderedEx(it[CourseExercise.id].value,
                            it[CourseExercise.orderIdx].toLong())
                }
    }

    val oldIdx = orderedExercises.indexOfFirst { it.id == courseExId }
    if (oldIdx == -1) {
        throw InvalidRequestException("No exercise with id $courseExId on course $courseId")
    }

    // Make sure the new index is not out of bounds
    val newIdx = min(max(rawNewIdx, 0), orderedExercises.size - 1)

    if (newIdx == oldIdx) {
        log.warn { "The exercise is already on position $newIdx" }
        return
    }

    data class PositionBetween(val prevIdx: Long, val nextIdx: Long)
    val newRealPosition = when (newIdx) {
        // Move to first
        0 -> {
            val next = orderedExercises[newIdx].idx
            PositionBetween(0, next)
        }
        // Move to last
        orderedExercises.size - 1 -> {
            val prev = orderedExercises[newIdx].idx
            PositionBetween(prev, prev + 2 * IDX_STEP)
        }
        else -> {
            if (newIdx > oldIdx) {
                // Moving up
                val prev = orderedExercises[newIdx].idx
                val next = orderedExercises[newIdx + 1].idx
                PositionBetween(prev, next)
            } else {
                // Moving down
                val prev = orderedExercises[newIdx - 1].idx
                val next = orderedExercises[newIdx].idx
                PositionBetween(prev, next)
            }
        }
    }

    log.debug { "New position is between ${newRealPosition.prevIdx} and ${newRealPosition.nextIdx}" }

    if (abs(newRealPosition.nextIdx - newRealPosition.prevIdx) < 2 ||
            newRealPosition.prevIdx + 2 * IDX_STEP > Int.MAX_VALUE) {
        // Normalise and try again
        log.info { "Bad new position, normalising" }
        normaliseCourseExIndices(courseId)
        setNewCourseExIndex(courseId, courseExId, newIdx)
        return
    }

    val newRealIdx = ((newRealPosition.nextIdx + newRealPosition.prevIdx) / 2).toInt()
    log.debug { "New real index: $newRealIdx" }
    updateCourseExIdx(courseExId, newRealIdx)
}


private fun updateCourseExIdx(id: Long, newIdx: Int) {
    transaction {
        CourseExercise.update({ CourseExercise.id eq id }) {
            it[orderIdx] = newIdx
        }
    }
}
