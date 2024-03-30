package core.ems.service.course

import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.studentOnCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders.CONTENT_DISPOSITION
import org.springframework.http.ResponseEntity
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RestController
@RequestMapping("/v2")
class ExportPersonalCourseLatestSubmissions {
    private val log = KotlinLogging.logger {}

    private data class Solution(val exerciseName: String, val submission: String, val createdAt: DateTime)
    private data class CourseSolution(val courseName: String, val solutions: List<Solution>)

    @Secured("ROLE_STUDENT")
    @GetMapping("/courses/{courseId}/export/submissions")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): ResponseEntity<ByteArrayResource> {
        log.info { "'${caller.id}' is exporting its own submissions in course '$courseIdStr'" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        caller.assertAccess { studentOnCourse(courseId) }

        val courseSolution = selectStudentSubmissions(courseId, caller.id)

        return ResponseEntity
            .ok()
            .header(CONTENT_DISPOSITION, "attachment; filename=${courseSolution.courseName}.zip")
            .body(zip(courseSolution))
    }

    private fun selectStudentSubmissions(courseId: Long, studentId: String): CourseSolution = transaction {
        val courseName = Course
            .select(Course.alias, Course.title)
            .where { Course.id eq courseId }
            .map { it[Course.alias] ?: it[Course.title] }
            .single()

        CourseSolution(courseName, (CourseExercise innerJoin Exercise innerJoin ExerciseVer innerJoin Submission)
            .select(
                CourseExercise.titleAlias,
                ExerciseVer.title,
                Submission.solution,
                Submission.createdAt
            )
            .where { CourseExercise.course eq courseId and (Submission.student eq studentId) and (ExerciseVer.validTo.isNull()) }
            .orderBy(Submission.createdAt, SortOrder.DESC)
            .distinctBy { it[Submission.solution] }
            .map {
                Solution(
                    it[CourseExercise.titleAlias] ?: it[ExerciseVer.title],
                    it[Submission.solution],
                    it[Submission.createdAt]
                )
            })
    }

    private fun zip(courseSolution: CourseSolution): ByteArrayResource {
        val zipOutputStream = ByteArrayOutputStream()

        ZipOutputStream(zipOutputStream).use { zipStream ->
            courseSolution.solutions.forEach {
                val entry = ZipEntry(it.exerciseName + ".py")
                entry.lastModifiedTime = FileTime.fromMillis(it.createdAt.millis)
                zipStream.putNextEntry(entry)
                zipStream.write(it.submission.toByteArray(Charsets.UTF_8))
                zipStream.closeEntry()
            }
        }
        return ByteArrayResource(zipOutputStream.toByteArray())
    }
}
