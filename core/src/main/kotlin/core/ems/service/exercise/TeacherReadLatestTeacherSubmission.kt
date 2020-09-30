package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.TeacherSubmission
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.servlet.http.HttpServletResponse

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherReadLatestTeacherSubmissionController {

    data class RespSubmission(@JsonProperty("id") val id: String,
                              @JsonProperty("solution") val solution: String,
                              @JsonSerialize(using = DateTimeSerializer::class)
                              @JsonProperty("created_at") val submissionTime: DateTime)


    data class Resp(@JsonProperty("count") val count: Int,
                    @JsonProperty("submissions") val submissions: List<RespSubmission>)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/exercises/{exerciseId}/testing/autoassess/submissions")
    fun controller(@PathVariable("exerciseId") exerciseIdStr: String,
                   response: HttpServletResponse,
                   @RequestParam("offset", required = false) offsetStr: String?,
                   @RequestParam("limit", required = false) limitStr: String?,
                   caller: EasyUser): Resp? {

        log.debug { "Getting latest teacher submissions for ${caller.id} on exercise $exerciseIdStr" }

        val exerciseId = exerciseIdStr.idToLongOrInvalidReq()
        return selectLatestTeacherSubmissions(exerciseId, caller.id, offsetStr?.toLongOrNull(), limitStr?.toIntOrNull())
    }
}


private fun selectLatestTeacherSubmissions(exerciseId: Long, teacherId: String, offset: Long?, limit: Int?): TeacherReadLatestTeacherSubmissionController.Resp {
    return transaction {
        val selectQuery = TeacherSubmission
                .select {
                    (TeacherSubmission.exercise eq exerciseId) and
                            (TeacherSubmission.teacher eq teacherId)
                }
                .orderBy(TeacherSubmission.createdAt to SortOrder.DESC)

        val totalSubmissions = selectQuery.count()

        val submissions = selectQuery
                .limit(limit ?: totalSubmissions.toInt(), offset ?: 0)
                .map {
                    TeacherReadLatestTeacherSubmissionController.RespSubmission(
                            it[TeacherSubmission.id].value.toString(),
                            it[TeacherSubmission.solution],
                            it[TeacherSubmission.createdAt])
                }

        TeacherReadLatestTeacherSubmissionController.Resp(submissions.size, submissions)
    }
}


