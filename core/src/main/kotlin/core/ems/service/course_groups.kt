package core.ems.service

import core.conf.security.EasyUser
import core.db.CourseGroup
import core.db.TeacherCourseGroup
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
        CourseGroup.select {
            CourseGroup.id eq groupId and (CourseGroup.course eq courseId)
        }.count() > 0
    }
}

fun getTeacherRestrictedCourseGroups(courseId: Long, caller: EasyUser): List<Long> {
    if (caller.isAdmin())
        return emptyList()

    return transaction {
        TeacherCourseGroup
                .slice(TeacherCourseGroup.courseGroup)
                .select {
                    TeacherCourseGroup.course eq courseId and
                            (TeacherCourseGroup.teacher eq caller.id)
                }.map { it[TeacherCourseGroup.courseGroup].value }
    }
}
