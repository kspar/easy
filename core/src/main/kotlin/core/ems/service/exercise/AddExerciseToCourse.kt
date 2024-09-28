package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.AdocService
import core.ems.service.IDX_STEP
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.isExerciseOnCourse
import core.ems.service.access_control.libraryExercise
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeDeserializer
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.Size

@RestController
@RequestMapping("/v2")
class AddExerciseToCourseCont(private val adocService: AdocService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("exercise_id") @field:Size(max = 100)
        val exerciseId: String,
        @JsonProperty("threshold") @field:Min(0) @field:Max(100)
        val threshold: Int,
        @JsonProperty("student_visible")
        val isStudentVisible: Boolean,
        @JsonDeserialize(using = DateTimeDeserializer::class)
        @JsonProperty("soft_deadline")
        val softDeadline: DateTime?,
        @JsonDeserialize(using = DateTimeDeserializer::class)
        @JsonProperty("hard_deadline")
        val hardDeadline: DateTime?,
        @JsonProperty("assessments_student_visible")
        val assStudentVisible: Boolean,
        @JsonProperty("instructions_adoc") @field:Size(max = 300000)
        val instructionsAdoc: String?,
        @JsonProperty("title_alias") @field:Size(max = 100)
        val titleAlias: String?
    )

    data class Resp(@JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ): Resp {
        log.info { "Adding exercise ${body.exerciseId} to course $courseIdString by ${caller.id}" }

        val courseId = courseIdString.idToLongOrInvalidReq()
        val exerciseId = body.exerciseId.idToLongOrInvalidReq()

        caller.assertAccess {
            teacherOnCourse(courseId)
            libraryExercise(exerciseId, DirAccessLevel.PR)
        }

        if (!isCoursePresent(courseId)) {
            throw InvalidRequestException("Course $courseId does not exist")
        }

        if (isExerciseOnCourse(exerciseId, courseId)) {
            throw InvalidRequestException(
                "Exercise $exerciseId is already on course $courseId",
                ReqError.EXERCISE_ALREADY_ON_COURSE, notify = false
            )
        }

        val id = when (body.instructionsAdoc) {
            null -> insertCourseExercise(courseId, body, null)
            else -> insertCourseExercise(courseId, body, adocService.adocToHtml(body.instructionsAdoc))
        }
        return Resp(id.toString())
    }

    private fun isCoursePresent(courseId: Long): Boolean = transaction {
        Course.selectAll().where { Course.id eq courseId }.count() > 0
    }

    private fun insertCourseExercise(courseId: Long, body: Req, html: String?): Long =
        transaction {
            val exerciseId = body.exerciseId.idToLongOrInvalidReq()
            val now = DateTime.now()
            val orderIdxMaxColumn = CourseExercise.orderIdx.max()

            val currentMaxOrderIdx = CourseExercise
                .select(orderIdxMaxColumn)
                .where { CourseExercise.course eq courseId }
                .groupBy(CourseExercise.course)
                .map { it[orderIdxMaxColumn] }
                .singleOrNull()

            val orderIndex = (currentMaxOrderIdx ?: 0) + IDX_STEP

            val studentVisibleFromTime = if (body.isStudentVisible) DateTime.now() else null

            val id = CourseExercise.insertAndGetId {
                it[course] = EntityID(courseId, Course)
                it[exercise] = EntityID(exerciseId, Exercise)
                it[createdAt] = now
                it[modifiedAt] = now
                it[gradeThreshold] = body.threshold
                it[softDeadline] = body.softDeadline
                it[hardDeadline] = body.hardDeadline
                it[orderIdx] = orderIndex
                it[studentVisibleFrom] = studentVisibleFromTime
                it[assessmentsStudentVisible] = body.assStudentVisible
                it[instructionsHtml] = html
                it[instructionsAdoc] = body.instructionsAdoc
                it[titleAlias] = body.titleAlias
            }

            if (html != null) {
                val inUse = StoredFile.select(StoredFile.id)
                    .where { StoredFile.usageConfirmed eq false }
                    .map { it[StoredFile.id].value }
                    .filter { html.contains(it) }

                StoredFile.update({ StoredFile.id inList inUse }) {
                    it[StoredFile.usageConfirmed] = true
                    it[StoredFile.exercise] = EntityID(exerciseId, Exercise)
                }
            }

            id.value
        }
}
