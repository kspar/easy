package core.ems.service

import core.db.CourseGroup
import core.exception.InvalidRequestException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction


fun assertGroupExistsOnCourse(groupId: Long, courseId: Long) {
    if (!groupExistsOnCourse(groupId, courseId)) {
        throw InvalidRequestException("Group $groupId not found on course $courseId")
    }
}

fun groupExistsOnCourse(groupId: Long, courseId: Long): Boolean = transaction {
    CourseGroup.selectAll().where { CourseGroup.id eq groupId and (CourseGroup.course eq courseId) }.count() > 0
}

