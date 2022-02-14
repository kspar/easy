package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.aas.selectAutoExercise
import core.conf.security.EasyUser
import core.db.CourseExercise
import core.db.Exercise
import core.db.ExerciseVer
import core.db.GraderType
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.util.DateTimeSerializer
import core.util.notNullAndInPast
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherReadExDetailsCont {

    data class Resp(
            @JsonProperty("exercise_id") val exerciseId: String,
            @JsonProperty("title") val title: String,
            @JsonProperty("title_alias") val titleAlias: String?,
            @JsonProperty("instructions_html") val instructionsHtml: String?,
            @JsonProperty("instructions_adoc") val instructionsAdoc: String?,
            @JsonProperty("text_html") val textHtml: String?,
            @JsonProperty("text_adoc") val textAdoc: String?,
            @JsonSerialize(using = DateTimeSerializer::class)
            @JsonProperty("soft_deadline") val softDeadline: DateTime?,
            @JsonSerialize(using = DateTimeSerializer::class)
            @JsonProperty("hard_deadline") val hardDeadline: DateTime?,
            @JsonProperty("grader_type") val grader: GraderType,
            @JsonProperty("threshold") val threshold: Int,
            @JsonSerialize(using = DateTimeSerializer::class)
            @JsonProperty("last_modified") val lastModified: DateTime,
            @JsonProperty("student_visible") val studentVisible: Boolean,
            @JsonSerialize(using = DateTimeSerializer::class)
            @JsonProperty("student_visible_from") val studentVisibleFrom: DateTime?,
            @JsonProperty("assessments_student_visible") val assStudentVisible: Boolean,
            @JsonProperty("grading_script") val gradingScript: String?,
            @JsonProperty("container_image") val containerImage: String?,
            @JsonProperty("max_time_sec") val maxTime: Int?,
            @JsonProperty("max_mem_mb") val maxMem: Int?,
            @JsonProperty("assets") val assets: List<RespAsset>?,
            @JsonProperty("executors") val executors: List<RespExecutor>?)

    data class RespAsset(@JsonProperty("file_name") val fileName: String,
                         @JsonProperty("file_content") val fileContent: String)

    data class RespExecutor(@JsonProperty("id") val id: String,
                            @JsonProperty("name") val name: String)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}")
    fun controller(@PathVariable("courseId") courseIdString: String,
                   @PathVariable("courseExerciseId") courseExerciseIdString: String,
                   caller: EasyUser): Resp {

        log.debug { "Getting exercise details for ${caller.id} for course exercise $courseExerciseIdString on course $courseIdString" }
        val courseId = courseIdString.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        return selectCourseExerciseDetails(courseId, courseExerciseIdString.idToLongOrInvalidReq())
                ?: throw InvalidRequestException("No course exercise found with id $courseExerciseIdString on course $courseId")
    }

}


private fun selectCourseExerciseDetails(courseId: Long, courseExId: Long): TeacherReadExDetailsCont.Resp? {
    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(Exercise.id,
                        CourseExercise.softDeadline,
                        CourseExercise.hardDeadline,
                        CourseExercise.gradeThreshold,
                        CourseExercise.studentVisibleFrom,
                        CourseExercise.assessmentsStudentVisible,
                        CourseExercise.instructionsHtml,
                        CourseExercise.instructionsAdoc,
                        CourseExercise.titleAlias,
                        ExerciseVer.title,
                        ExerciseVer.textHtml,
                        ExerciseVer.textAdoc,
                        ExerciseVer.graderType,
                        ExerciseVer.validFrom,
                        ExerciseVer.autoExerciseId
                )
                .select {
                    CourseExercise.course eq courseId and
                            (CourseExercise.id eq courseExId) and
                            ExerciseVer.validTo.isNull()
                }
                .map {
                    val graderType = it[ExerciseVer.graderType]

                    val autoExercise =
                            if (graderType == GraderType.AUTO) {
                                val autoExerciseId = it[ExerciseVer.autoExerciseId]
                                        ?: throw IllegalStateException("Exercise grader type is AUTO but auto exercise id is null")
                                selectAutoExercise(autoExerciseId)
                            } else null

                    TeacherReadExDetailsCont.Resp(
                            it[Exercise.id].value.toString(),
                            it[ExerciseVer.title],
                            it[CourseExercise.titleAlias],
                            it[CourseExercise.instructionsHtml],
                            it[CourseExercise.instructionsAdoc],
                            it[ExerciseVer.textHtml],
                            it[ExerciseVer.textAdoc],
                            it[CourseExercise.softDeadline],
                            it[CourseExercise.hardDeadline],
                            it[ExerciseVer.graderType],
                            it[CourseExercise.gradeThreshold],
                            it[ExerciseVer.validFrom],
                            it[CourseExercise.studentVisibleFrom].notNullAndInPast(),
                            it[CourseExercise.studentVisibleFrom],
                            it[CourseExercise.assessmentsStudentVisible],
                            autoExercise?.gradingScript,
                            autoExercise?.containerImage,
                            autoExercise?.maxTime,
                            autoExercise?.maxMem,
                            autoExercise?.assets?.map { TeacherReadExDetailsCont.RespAsset(it.first, it.second) },
                            autoExercise?.executors?.map { TeacherReadExDetailsCont.RespExecutor(it.id.toString(), it.name) }
                    )
                }
                .singleOrNull()
    }
}
