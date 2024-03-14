package core.ems.service

import core.db.CourseGroup
import core.exception.InvalidRequestException
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction


fun assertGroupExistsOnCourse(groupId: Long, courseId: Long) {
    if (!groupExistsOnCourse(groupId, courseId)) {
        throw InvalidRequestException("Group $groupId not found on course $courseId")
    }
}

fun groupExistsOnCourse(groupId: Long, courseId: Long): Boolean = transaction {
    CourseGroup.selectAll().where { CourseGroup.id eq groupId and (CourseGroup.course eq courseId) }.count() > 0
}

