package ee.urgas.ems.bl.access

import ee.urgas.ems.db.StudentCourseAccess
import ee.urgas.ems.db.Teacher
import ee.urgas.ems.db.TeacherCourseAccess
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


fun canTeacherAccessCourse(teacherEmail: String, courseId: Long): Boolean {
    return transaction {
        // TODO: remove join
        (Teacher innerJoin TeacherCourseAccess)
                .select { Teacher.id eq teacherEmail and (TeacherCourseAccess.course eq courseId) }
                .count() > 0
    }
}


fun canStudentAccessCourse(studentEmail: String, courseId: Long): Boolean {
    return transaction {
        StudentCourseAccess
                .select { StudentCourseAccess.student eq studentEmail and
                        (StudentCourseAccess.course eq courseId) }
                .count() > 0
    }
}

