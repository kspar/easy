package ee.urgas.ems.bl.access

import ee.urgas.ems.db.Teacher
import ee.urgas.ems.db.TeacherCourseAccess
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


fun canTeacherAccessCourse(teacherEmail: String, courseId: Long): Boolean {
    return transaction {
        (Teacher innerJoin TeacherCourseAccess)
                .select { Teacher.id eq teacherEmail and (TeacherCourseAccess.course eq courseId) }
                .count() > 0
    }
}

