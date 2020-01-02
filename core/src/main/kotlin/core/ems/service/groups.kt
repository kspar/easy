package core.ems.service

import core.db.Group
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
