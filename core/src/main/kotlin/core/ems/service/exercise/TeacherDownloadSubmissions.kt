package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.assertTeacherOrAdminHasAccessToCourseGroup
import core.ems.service.getTeacherRestrictedCourseGroups
import core.ems.service.idToLongOrInvalidReq
import core.util.Zip
import core.util.writeZipFile
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletResponse
import javax.validation.Valid
import javax.validation.constraints.NotEmpty

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherDownloadSubmissionsController {

    data class Req(@JsonProperty("courses") @field:NotEmpty val courses: List<CourseReq>)

    data class CourseReq(@JsonProperty("id") val id: String,
                         @JsonProperty("groups") val groups: List<GroupReq>?)

    data class GroupReq(@JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/export/exercises/{exerciseId}/submissions/latest")
    fun controller(@PathVariable("exerciseId") exerciseIdStr: String,
                   @Valid @RequestBody req: Req,
                   caller: EasyUser,
                   response: HttpServletResponse) {

        log.debug { "${caller.id} is downloading submissions for exercise $exerciseIdStr on courses ${req.courses} " }

        val exerciseId = exerciseIdStr.idToLongOrInvalidReq()

        // Check access to courses and groups
        req.courses.forEach { course ->
            val courseId = course.id.idToLongOrInvalidReq()
            assertTeacherOrAdminHasAccessToCourse(caller, courseId)
            course.groups?.forEach {
                assertTeacherOrAdminHasAccessToCourseGroup(caller, courseId, it.id.idToLongOrInvalidReq())
            }
        }

        response.contentType = "application/zip"
        response.setHeader("Content-disposition", "attachment; filename=submissions.zip")

        writeZipFile(
                req.courses.map { course ->
                    course.id.idToLongOrInvalidReq() to course.groups.orEmpty().map { it.id.idToLongOrInvalidReq() }
                }.flatMap { (courseId, groupIds) ->
                    selectSubmission(exerciseId, courseId, groupIds, caller)
                },
                response.outputStream)
    }
}


private fun selectSubmission(exerciseId: Long, courseId: Long, groupIds: List<Long>, caller: EasyUser): List<Zip> {
    return transaction {

        val join1 = Submission innerJoin CourseExercise innerJoin (Student innerJoin Account)
        val join2 = StudentCourseAccess leftJoin (StudentCourseGroup innerJoin CourseGroup)

        val query = Join(join1, join2, onColumn = Submission.student, otherColumn = StudentCourseAccess.student)
                .slice(Submission.solution,
                        Submission.student,
                        Account.givenName,
                        Account.familyName,
                        CourseGroup.name)
                .select {
                    CourseExercise.exercise eq exerciseId and
                            (CourseExercise.course eq courseId) and
                            (StudentCourseAccess.course eq courseId)
                }
                .orderBy(Submission.createdAt, SortOrder.DESC)

        when {
            groupIds.isNotEmpty() -> query.andWhere { StudentCourseGroup.courseGroup inList groupIds }
            else -> {
                val restrictedGroups = getTeacherRestrictedCourseGroups(courseId, caller)
                if (restrictedGroups.isNotEmpty()) {
                    query.andWhere {
                        StudentCourseGroup.courseGroup inList restrictedGroups or (StudentCourseGroup.courseGroup.isNull())
                    }
                }
            }
        }

        query.distinctBy { it[Submission.student] }
                .map {
                    Zip(it[Submission.solution],
                            createFileName(it[Account.givenName],
                                    it[Account.familyName],
                                    courseId,
                                    it[CourseGroup.name]))
                }
    }
}

private fun createFileName(givenName: String, familyName: String, courseId: Long, group: String?): String {
    return "${givenName}_${familyName}_${courseId}${if (group != null) "_$group" else ""}.py".replace(" ", "_")
}

