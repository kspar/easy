package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertGroupExistsOnCourse
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.assertTeacherOrAdminHasAccessToCourseGroup
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.fillParameters
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AddStudentsToCourseController {

    data class Req(@JsonProperty("students") @field:Valid val students: List<StudentEmailReq>)

    data class StudentEmailReq(
            @JsonProperty("email") @field:NotBlank @field:Size(max = 100) val studentEmail: String,
            @JsonProperty("groups") @field:Valid val groups: List<GroupReq> = emptyList())

    data class GroupReq(@JsonProperty("id") @field:NotBlank @field:Size(max = 100) val groupId: String)

    data class Resp(@JsonProperty("accesses_added") val accessesAdded: Int,
                    @JsonProperty("pending_accesses_added_updated") val pendingAccessesAddedUpdated: Int)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/students")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @RequestBody @Valid body: Req, caller: EasyUser): Resp {

        log.debug { "Adding access to course $courseIdStr to students $body" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        val students = body.students.map {
            StudentNoAccount(
                    it.studentEmail.toLowerCase(),
                    it.groups.map {
                        it.groupId.idToLongOrInvalidReq()
                    }.toSet()
            )
        }.distinctBy { it.email }

        students.flatMap { it.groups }
                .toSet()
                .forEach {
                    assertGroupExistsOnCourse(it, courseId)
                    assertTeacherOrAdminHasAccessToCourseGroup(caller, courseId, it)
                }

        return insertStudentCourseAccesses(courseId, students)
    }
}

private data class StudentWithAccount(val id: String, val email: String, val groups: Set<Long>)
private data class StudentNoAccount(val email: String, val groups: Set<Long>)

private fun insertStudentCourseAccesses(courseId: Long, students: List<StudentNoAccount>):
        AddStudentsToCourseController.Resp {

    val now = DateTime.now()

    return transaction {
        val studentsWithAccount = mutableSetOf<StudentWithAccount>()
        val studentsNoAccount = mutableSetOf<StudentNoAccount>()

        students.forEach {
            val studentId = (Student innerJoin Account)
                    .slice(Student.id)
                    .select { Account.email.lowerCase() eq it.email }
                    .map { it[Student.id].value }
                    .singleOrNull()

            if (studentId != null) {
                studentsWithAccount.add(StudentWithAccount(studentId, it.email, it.groups))
            } else {
                studentsNoAccount.add(StudentNoAccount(it.email, it.groups))
            }
        }

        val newStudentsWithAccount = studentsWithAccount.filter {
            StudentCourseAccess.select {
                StudentCourseAccess.student eq it.id and (StudentCourseAccess.course eq courseId)
            }.count() == 0
        }

        log.debug { "Granting access to students (the rest already have access): $newStudentsWithAccount" }
        log.debug { "Granting pending access to students: $studentsNoAccount" }

        StudentCourseAccess.batchInsert(newStudentsWithAccount) {
            this[StudentCourseAccess.student] = EntityID(it.id, Student)
            this[StudentCourseAccess.course] = EntityID(courseId, Course)
        }

        newStudentsWithAccount.forEach { student ->
            StudentGroupAccess.batchInsert(student.groups) {
                this[StudentGroupAccess.student] = EntityID(student.id, Student)
                this[StudentGroupAccess.course] = EntityID(courseId, Course)
                this[StudentGroupAccess.group] = EntityID(it, Group)
            }
        }

        // Delete & create pending accesses to reset validFrom
        // TODO: use upsert instead?
        StudentPendingGroup.deleteWhere {
            StudentPendingGroup.course eq courseId and
                    (StudentPendingGroup.email inList studentsNoAccount.map { it.email })
        }

        StudentPendingAccess.deleteWhere {
            StudentPendingAccess.course eq courseId and
                    (StudentPendingAccess.email inList studentsNoAccount.map { it.email })
        }
        // TODO: why ignore = true?
        StudentPendingAccess.batchInsert(studentsNoAccount, ignore = true) {
            this[StudentPendingAccess.course] = EntityID(courseId, Course)
            this[StudentPendingAccess.email] = it.email
            this[StudentPendingAccess.validFrom] = now
        }

        studentsNoAccount.forEach { student ->
            //            StudentPendingGroup.batchInsert(student.groups, ignore = true) {
//                this[StudentPendingGroup.email] = student.email
//                this[StudentPendingGroup.course] = EntityID(courseId, Course)
//                this[StudentPendingGroup.group] = EntityID(it, Group)
//            }

            // https://github.com/JetBrains/Exposed/issues/639
            // WHOML (Worst Hack Of My Life)
            // TODO: fix
            if (student.groups.isNotEmpty()) {
                val placeholders = student.groups.joinToString { "(?, ?, ?)" }
                val values = student.groups.flatMap {
                    listOf(TextColumnType() to student.email,
                            LongColumnType() to courseId,
                            LongColumnType() to it)
                }

                val sql = "insert into student_pending_group_access (email, course_id, group_id) VALUES $placeholders;"
                val s = TransactionManager.current().connection.prepareStatement(sql)
                s.fillParameters(values)
                s.executeUpdate()
            }
        }

        AddStudentsToCourseController.Resp(newStudentsWithAccount.size, studentsNoAccount.size)
    }
}
