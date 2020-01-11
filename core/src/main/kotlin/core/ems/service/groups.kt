package core.ems.service

import core.db.Group
import core.db.TeacherGroupAccess
import core.exception.InvalidRequestException
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


fun assertGroupExistsOnCourse(groupId: Long, courseId: Long) {
    if (!groupExistsOnCourse(groupId, courseId)) {
        throw InvalidRequestException("Group $groupId not found on course $courseId")
    }
}

fun groupExistsOnCourse(groupId: Long, courseId: Long): Boolean {
    return transaction {
        Group.select {
            Group.id eq groupId and (Group.course eq courseId)
        }.count() > 0
    }
}

fun getTeacherRestrictedGroups(courseId: Long, callerId: String): List<Long> {
    return transaction {
        TeacherGroupAccess
                .slice(TeacherGroupAccess.group)
                .select {
                    TeacherGroupAccess.course eq courseId and
                            (TeacherGroupAccess.teacher eq callerId)
                }.map { it[TeacherGroupAccess.group].value }
    }
}
