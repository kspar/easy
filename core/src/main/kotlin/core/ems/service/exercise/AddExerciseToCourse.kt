package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.MarkdownService
import core.ems.service.rejectLegacyContentFields
import core.ems.service.IDX_STEP
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryExercise
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.util.DateTimeDeserializer
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.annotation.JsonDeserialize


@RestController
@RequestMapping("/v2")
class AddExerciseToCourseCont(private val markdownService: MarkdownService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("exercise_id") @field:Size(max = 100)
        val exerciseId: String,
        @param:JsonProperty("threshold") @field:Min(0) @field:Max(100)
        val threshold: Int,
        @param:JsonProperty("student_visible")
        val isStudentVisible: Boolean,
        @param:JsonDeserialize(using = DateTimeDeserializer::class)
        @param:JsonProperty("soft_deadline")
        val softDeadline: DateTime?,
        @param:JsonDeserialize(using = DateTimeDeserializer::class)
        @param:JsonProperty("hard_deadline")
        val hardDeadline: DateTime?,
        @param:JsonProperty("assessments_student_visible")
        val assStudentVisible: Boolean,
        @param:JsonProperty("instructions_md") @field:Size(max = 300000)
        val instructionsMd: String?,
        // Rejected, never read — see rejectLegacyContentFields (EZ-1730).
        @param:JsonProperty("instructions_adoc") val legacyInstructionsAdoc: String? = null,
        @param:JsonProperty("title_alias") @field:Size(max = 100)
        val titleAlias: String?
    )

    data class Resp(@get:JsonProperty("id") val id: String)

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

        rejectLegacyContentFields("instructions_md", "instructions_adoc" to body.legacyInstructionsAdoc)

        val id = insertCourseExercise(courseId, body, body.instructionsMd?.let { markdownService.mdToHtml(it) })
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
                it[instructionsMd] = body.instructionsMd
                it[titleAlias] = body.titleAlias
            }

            id.value
        }
}
