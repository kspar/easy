package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders.CONTENT_DISPOSITION
import org.springframework.http.ResponseEntity
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RestController
@RequestMapping("/v2")
class ExportPersonalData {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/account/export")
    fun controller(caller: EasyUser): ResponseEntity<ByteArrayResource> {
        log.info { "'${caller.id}' is exporting personal data." }

        val jsons = listOfNotNull(
            JsonFile("account.json", selectAccountJSON(caller.id)),
            JsonFile("log_reports.json", selectLogReportsJSON(caller.id)),
            if (caller.isStudent()) JsonFile("submissions.json", selectSubmissionsJSON(caller.id)) else null,
            if (caller.isStudent()) JsonFile("drafts.json", selectSubmissionsDraftJSON(caller.id)) else null
        )

        return ResponseEntity
            .ok()
            .header(CONTENT_DISPOSITION, "attachment; filename=personal-data.zip")
            .body(zip(jsons))
    }


    private fun selectAccountJSON(userId: String): String {
        data class AccountDataJSON(
            @get:JsonProperty("id") val username: String,
            @get:JsonProperty("created_at") @get:JsonSerialize(using = DateTimeSerializer::class) val createdAt: DateTime,
            @get:JsonProperty("email") val email: String,
            @get:JsonProperty("given_name") val givenName: String,
            @get:JsonProperty("family_name") val familyName: String
        )

        return transaction {
            jacksonObjectMapper().writeValueAsString(
                Account.select(
                    Account.id,
                    Account.createdAt,
                    Account.email,
                    Account.givenName,
                    Account.familyName
                ).where { Account.id eq userId }.map {
                    AccountDataJSON(
                        it[Account.id].value,
                        it[Account.createdAt],
                        it[Account.email],
                        it[Account.givenName],
                        it[Account.familyName]
                    )
                }.single()
            )
        }
    }


    private fun selectLogReportsJSON(userId: String): String {
        data class LogReportDataJSON(
            @get:JsonProperty("id") val id: Long,
            @get:JsonProperty("log_time") @get:JsonSerialize(using = DateTimeSerializer::class) val logTime: DateTime,
            @get:JsonProperty("level") val level: String,
            @get:JsonProperty("message") val message: String,
            @get:JsonProperty("client_id") val clientId: String
        )

        return transaction {
            jacksonObjectMapper().writeValueAsString(
                LogReport.select(
                    LogReport.id,
                    LogReport.logTime,
                    LogReport.logLevel,
                    LogReport.logMessage,
                    LogReport.clientId
                ).where { LogReport.userId eq userId }.map {
                    LogReportDataJSON(
                        it[LogReport.id].value,
                        it[LogReport.logTime],
                        it[LogReport.logLevel],
                        it[LogReport.logMessage],
                        it[LogReport.clientId],
                    )
                })
        }
    }


    private fun selectSubmissionsJSON(studentId: String): String {
        data class SubmissionDataJSON(
            @get:JsonProperty("id") val submissionId: String,
            @get:JsonProperty("solution") val solution: String,
            @get:JsonSerialize(using = DateTimeSerializer::class) @get:JsonProperty("created_at") val createdAt: DateTime,
            @get:JsonProperty("grade_auto") val gradeAuto: Int?,
            @get:JsonProperty("feedback_auto") val feedbackAuto: String?,
            @get:JsonProperty("grade_teacher") val gradeTeacher: Int?,
            @get:JsonProperty("feedback_teacher") val feedbackTeacher: String?
        )

        return transaction {
            jacksonObjectMapper().writeValueAsString(
                Submission.select(
                    Submission.createdAt,
                    Submission.id,
                    Submission.solution
                ).where {
                    (Submission.student eq studentId)
                }.orderBy(Submission.createdAt, SortOrder.DESC).map {
                    val id = it[Submission.id].value
                    val autoAssessment = lastAutogradeActivity(id)
                    val teacherAssessment = lastTeacherActivity(id)
                    SubmissionDataJSON(
                        id.toString(),
                        it[Submission.solution],
                        it[Submission.createdAt],
                        autoAssessment?.first,
                        autoAssessment?.second,
                        teacherAssessment?.first,
                        teacherAssessment?.second
                    )
                })
        }
    }

    private fun selectSubmissionsDraftJSON(studentId: String): String {
        data class SubmissionDraftJSON(
            @get:JsonProperty("course_exercise_id") val courseExerciseId: Long,
            @get:JsonSerialize(using = DateTimeSerializer::class) @get:JsonProperty("created_at") val createdAt: DateTime,
            @get:JsonProperty("draft") val draft: String
        )

        return transaction {
            jacksonObjectMapper().writeValueAsString(
                SubmissionDraft.select(
                    SubmissionDraft.courseExercise,
                    SubmissionDraft.createdAt,
                    SubmissionDraft.solution
                ).where {
                    (SubmissionDraft.student eq studentId)
                }.orderBy(SubmissionDraft.createdAt, SortOrder.DESC).map {
                    SubmissionDraftJSON(
                        it[SubmissionDraft.courseExercise].value,
                        it[SubmissionDraft.createdAt],
                        it[SubmissionDraft.solution]
                    )
                })
        }
    }


    private fun lastAutogradeActivity(submissionId: Long): Pair<Int, String?>? =
        AutogradeActivity.selectAll().where { AutogradeActivity.submission eq submissionId }
            .orderBy(AutogradeActivity.createdAt to SortOrder.DESC).limit(1)
            .map { it[AutogradeActivity.grade] to it[AutogradeActivity.feedback] }.firstOrNull()

    private fun lastTeacherActivity(submissionId: Long): Pair<Int?, String?>? =
        TeacherActivity.selectAll().where { TeacherActivity.submission eq submissionId }
            .orderBy(TeacherActivity.mergeWindowStart to SortOrder.DESC).limit(1)
            .map { it[TeacherActivity.grade] to it[TeacherActivity.feedback] }.firstOrNull()

    private data class JsonFile(val filename: String, val content: String)

    private fun zip(files: List<JsonFile>): ByteArrayResource {
        val zipOutputStream = ByteArrayOutputStream()

        ZipOutputStream(zipOutputStream).use { zipStream ->
            files.forEach {
                val entry = ZipEntry(it.filename)
                zipStream.putNextEntry(entry)
                zipStream.write(it.content.toByteArray(Charsets.UTF_8))
                zipStream.closeEntry()
            }
        }
        return ByteArrayResource(zipOutputStream.toByteArray())
    }
}
