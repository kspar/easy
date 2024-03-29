package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import core.conf.security.EasyUser
import core.db.*
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders.CONTENT_DISPOSITION
import org.springframework.http.ResponseEntity
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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
        log.debug { "'${caller.id}' is exporting personal data." }

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
            @JsonProperty("id") val username: String,
            @JsonProperty("created_at") @JsonSerialize(using = DateTimeSerializer::class) val createdAt: DateTime,
            @JsonProperty("moodle_username") val moodleUsername: String?,
            @JsonProperty("email") val email: String,
            @JsonProperty("given_name") val givenName: String,
            @JsonProperty("family_name") val familyName: String
        )

        return transaction {
            jacksonObjectMapper().writeValueAsString(Account.slice(
                Account.id,
                Account.createdAt,
                Account.moodleUsername,
                Account.email,
                Account.givenName,
                Account.familyName
            ).select { Account.id eq userId }.map {
                AccountDataJSON(
                    it[Account.id].value,
                    it[Account.createdAt],
                    it[Account.moodleUsername],
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
            @JsonProperty("id") val id: Long,
            @JsonProperty("log_time") @JsonSerialize(using = DateTimeSerializer::class) val logTime: DateTime,
            @JsonProperty("level") val level: String,
            @JsonProperty("message") val message: String,
            @JsonProperty("client_id") val clientId: String
        )

        return transaction {
            jacksonObjectMapper().writeValueAsString(LogReport.slice(
                LogReport.id,
                LogReport.logTime,
                LogReport.logLevel,
                LogReport.logMessage,
                LogReport.clientId
            ).select { LogReport.userId eq userId }.map {
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
            @JsonProperty("id") val submissionId: String,
            @JsonProperty("solution") val solution: String,
            @JsonSerialize(using = DateTimeSerializer::class) @JsonProperty("created_at") val createdAt: DateTime,
            @JsonProperty("grade_auto") val gradeAuto: Int?,
            @JsonProperty("feedback_auto") val feedbackAuto: String?,
            @JsonProperty("grade_teacher") val gradeTeacher: Int?,
            @JsonProperty("feedback_teacher") val feedbackTeacher: String?
        )

        return transaction {
            jacksonObjectMapper().writeValueAsString(Submission.slice(
                Submission.createdAt,
                Submission.id,
                Submission.solution
            ).select {
                (Submission.student eq studentId)
            }.orderBy(Submission.createdAt, SortOrder.DESC).map {
                val id = it[Submission.id].value
                val autoAssessment = lastAutoAssessment(id)
                val teacherAssessment = lastTeacherAssessment(id)
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
            @JsonProperty("course_exercise_id") val courseExerciseId: Long,
            @JsonSerialize(using = DateTimeSerializer::class) @JsonProperty("created_at") val createdAt: DateTime,
            @JsonProperty("draft") val draft: String
        )

        return transaction {
            jacksonObjectMapper().writeValueAsString(SubmissionDraft.slice(
                SubmissionDraft.courseExercise,
                SubmissionDraft.createdAt,
                SubmissionDraft.solution
            ).select {
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


    private fun lastAutoAssessment(submissionId: Long): Pair<Int, String?>? =
        AutomaticAssessment.select { AutomaticAssessment.submission eq submissionId }
            .orderBy(AutomaticAssessment.createdAt to SortOrder.DESC).limit(1)
            .map { it[AutomaticAssessment.grade] to it[AutomaticAssessment.feedback] }.firstOrNull()

    private fun lastTeacherAssessment(submissionId: Long): Pair<Int, String?>? =
        TeacherAssessment.select { TeacherAssessment.submission eq submissionId }
            .orderBy(TeacherAssessment.createdAt to SortOrder.DESC).limit(1)
            .map { it[TeacherAssessment.grade] to it[TeacherAssessment.feedback] }.firstOrNull()

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
