package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Account
import core.db.TeacherAssessment
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


data class TeacherResp(
    @JsonProperty("id")
    val id: String,
    @JsonProperty("given_name")
    val givenName: String,
    @JsonProperty("family_name")
    val familyName: String
)


fun selectTeacher(teacherId: String) = transaction {
    Account
        .slice(Account.id, Account.givenName, Account.familyName)
        .select { Account.id eq teacherId }
        .map { TeacherResp(it[Account.id].value, it[Account.givenName], it[Account.familyName]) }
        .singleOrInvalidRequest()
}




fun assertAssessmentControllerChecks(
    caller: EasyUser, submissionIdString: String, courseExerciseIdString: String, courseIdString: String,
): Triple<String, Long, Long> {

    val callerId = caller.id
    val courseId = courseIdString.idToLongOrInvalidReq()
    val courseExId = courseExerciseIdString.idToLongOrInvalidReq()
    val submissionId = submissionIdString.idToLongOrInvalidReq()

    caller.assertAccess { teacherOnCourse(courseId, true) }

    assertSubmissionExists(submissionId, courseExId, courseId)
    return Triple(callerId, courseExId, submissionId)
}
