package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertTeacherHasAccessToCourse
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.getTeacherRestrictedGroups
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

    data class CourseReq(@JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/export/exercises/{exerciseId}/submissions/latest")
    fun controller(@PathVariable("exerciseId") exerciseIdStr: String,
                   @Valid @RequestBody req: Req,
                   caller: EasyUser,
                   response: HttpServletResponse) {

        log.debug { "${caller.id} is downloading submissions for exercise $exerciseIdStr on courses ${req.courses} " }

        val exerciseId = exerciseIdStr.idToLongOrInvalidReq()

        response.contentType = "application/zip"
        response.setHeader("Content-disposition", "attachment; filename=submissions.zip")

        // Check that 1) courses exists and 2) teacher has access to them
        writeZipFile(
                req.courses.map {
                    it.id.idToLongOrInvalidReq()
                }.flatMap {
                    assertTeacherOrAdminHasAccessToCourse(caller, it)
                    selectSubmission(exerciseId, it, caller.id)
                },
                response.outputStream)
    }
}


private fun selectSubmission(exerciseId: Long, courseId: Long, callerId: String): List<Zip> {
    return transaction {

        val restrictedGroups = getTeacherRestrictedGroups(courseId, callerId)

        val join1 = Submission innerJoin CourseExercise innerJoin (Student innerJoin Account)
        val join2 = StudentCourseAccess leftJoin (StudentGroupAccess innerJoin Group)

        val query = Join(join1, join2, onColumn = Submission.student, otherColumn = StudentCourseAccess.student)
                .slice(Submission.solution,
                        Submission.student,
                        Account.givenName,
                        Account.familyName,
                        Group.name)
                .select {
                    CourseExercise.exercise eq exerciseId and
                            (CourseExercise.course eq courseId) and
                            (StudentCourseAccess.course eq courseId)
                }
                .orderBy(Submission.createdAt, SortOrder.DESC)

        if (restrictedGroups.isNotEmpty()) {
            query.andWhere {
                StudentGroupAccess.group inList restrictedGroups or (StudentGroupAccess.group.isNull())
            }
        }

        query.distinctBy { it[Submission.student] }
                .map {
                    Zip(it[Submission.solution],
                            createFileName(it[Account.givenName],
                                    it[Account.familyName],
                                    courseId,
                                    it[Group.name]))
                }
    }
}

private fun createFileName(givenName: String, familyName: String, courseId: Long, group: String?): String {
    return "${givenName}_${familyName}_${courseId}${if (group != null) "_$group" else ""}.py".replace(" ", "_")
}

