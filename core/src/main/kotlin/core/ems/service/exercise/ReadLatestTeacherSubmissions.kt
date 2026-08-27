package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.TeacherSubmission
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class ReadLatestTeacherSubmissions {
    private val log = KotlinLogging.logger {}

    data class RespSubmission(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("solution") val solution: String,
        // Null when the run never produced one — an executor failure, or any submission made
        // before the result was stored at all (changeset 120826-1).
        @get:JsonProperty("grade") val grade: Int?,
        @get:JsonProperty("feedback") val feedback: String?,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val submissionTime: DateTime
    )

    data class Resp(
        @get:JsonProperty("count") val count: Int,
        @get:JsonProperty("submissions") val submissions: List<RespSubmission>
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/exercises/{exerciseId}/testing/autoassess/submissions")
    fun controller(
        @PathVariable("exerciseId") exerciseIdStr: String,
        @RequestParam("offset", required = false) offsetStr: String?,
        @RequestParam("limit", required = false) limitStr: String?,
        caller: EasyUser
    ): Resp {

        log.info { "Getting latest teacher submissions for ${caller.id} on exercise $exerciseIdStr" }
        val exerciseId = exerciseIdStr.idToLongOrInvalidReq()
        return selectLatestTeacherSubmissions(exerciseId, caller.id, offsetStr?.toLongOrNull(), limitStr?.toIntOrNull())
    }

    private fun selectLatestTeacherSubmissions(exerciseId: Long, teacherId: String, offset: Long?, limit: Int?): Resp =
        transaction {
            val selectQuery = TeacherSubmission
                .selectAll().where {
                    (TeacherSubmission.exercise eq exerciseId) and
                            (TeacherSubmission.teacher eq teacherId)
                }
                // `id` breaks the tie, and this list is paginated, so it is not cosmetic. Two
                // submissions can share a `created_at` — a teacher testing an exercise submits in
                // bursts — and rows tied on the whole sort key may come back in any order, including a
                // different order for `offset 0` than for `offset 10`. That loses a row from one page
                // and repeats it on another. `id` is unique and monotonic, so the sort is now total.
                .orderBy(TeacherSubmission.createdAt to SortOrder.DESC, TeacherSubmission.id to SortOrder.DESC)

            val totalSubmissions = selectQuery.count()

            val submissions = selectQuery
                .limit(limit ?: totalSubmissions.toInt())
                .offset(offset ?: 0)
                .map {
                    RespSubmission(
                        it[TeacherSubmission.id].value.toString(),
                        it[TeacherSubmission.solution],
                        it[TeacherSubmission.grade],
                        it[TeacherSubmission.feedback],
                        it[TeacherSubmission.createdAt]
                    )
                }

            // The total, not `submissions.size`. `count` used to be the size of the page, which equals
            // the total only when nothing is paginated — so it was wrong in precisely the case a
            // `count` field exists for, and a client asking for a page could not learn how many rows
            // there were. The COUNT(*) was already being issued on every request and then discarded.
            Resp(totalSubmissions.toInt(), submissions)
        }
}
