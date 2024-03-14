package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.Zip
import core.util.writeZipFile
import mu.KotlinLogging
import org.jetbrains.exposed.sql.Join
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletResponse
import javax.validation.Valid
import javax.validation.constraints.NotEmpty


@RestController
@RequestMapping("/v2")
class TeacherDownloadSubmissionsController {
    private val log = KotlinLogging.logger {}

    data class Req(@JsonProperty("courses") @field:NotEmpty val courses: List<CourseReq>)

    data class CourseReq(@JsonProperty("id") val id: String, @JsonProperty("groups") val groups: List<GroupReq>?)

    data class GroupReq(@JsonProperty("id") val id: String)

    private data class Course(val id: Long, val groups: List<Long>?)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/export/exercises/{exerciseId}/submissions/latest")
    fun controller(
        @PathVariable("exerciseId") exerciseIdStr: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser,
        response: HttpServletResponse
    ) {

        log.info { "${caller.id} is downloading submissions for exercise $exerciseIdStr on courses ${req.courses} " }

        val exerciseId = exerciseIdStr.idToLongOrInvalidReq()

        val courses = req.courses.map {
            Course(it.id.idToLongOrInvalidReq(),
                it.groups?.map { group -> group.id.idToLongOrInvalidReq() }
            )
        }

        // Check access to courses
        caller.assertAccess { courses.forEach { course -> teacherOnCourse(course.id) } }

        response.contentType = "application/zip"
        response.setHeader("Content-disposition", "attachment; filename=submissions.zip")

        writeZipFile(
            courses.map {
                it.id to it.groups.orEmpty()
            }.flatMap { (courseId, groupIds) ->
                selectSubmission(exerciseId, courseId, groupIds)
            },
            response.outputStream
        )
    }

    private fun selectSubmission(exerciseId: Long, courseId: Long, groupIds: List<Long>): List<Zip> =
        transaction {

            val join1 = Submission innerJoin CourseExercise innerJoin Account
            val join2 = StudentCourseAccess leftJoin (StudentCourseGroup innerJoin CourseGroup)

            val query = Join(join1, join2, onColumn = Submission.student, otherColumn = StudentCourseAccess.student)
                .select(
                    Submission.solution,
                    Submission.student,
                    Account.givenName,
                    Account.familyName,
                    CourseGroup.name
                )
                .where {
                    CourseExercise.exercise eq exerciseId and
                            (CourseExercise.course eq courseId) and
                            (StudentCourseAccess.course eq courseId)
                }
                .orderBy(Submission.createdAt, SortOrder.DESC)

            if (groupIds.isNotEmpty()) {
                query.andWhere { StudentCourseGroup.courseGroup inList groupIds }
            }

            query.distinctBy { it[Submission.student] }
                .map {
                    Zip(
                        it[Submission.solution],
                        createFileName(
                            it[Account.givenName],
                            it[Account.familyName],
                            courseId,
                            it[CourseGroup.name]
                        )
                    )
                }
        }

    private fun createFileName(givenName: String, familyName: String, courseId: Long, group: String?): String =
        "${givenName}_${familyName}_${courseId}${if (group != null) "_$group" else ""}.py".replace(" ", "_")
}


