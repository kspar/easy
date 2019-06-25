package ee.urgas.ems.bl.access

import ee.urgas.ems.db.CourseExercise
import ee.urgas.ems.db.StudentCourseAccess
import ee.urgas.ems.db.Teacher
import ee.urgas.ems.db.TeacherCourseAccess
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


fun canTeacherAccessCourse(teacherId: String, courseId: Long): Boolean {
    return transaction {
        // TODO: remove join
        (Teacher innerJoin TeacherCourseAccess)
                .select { Teacher.id eq teacherId and (TeacherCourseAccess.course eq courseId) }
                .count() > 0
    }
}


fun canStudentAccessCourse(studentId: String, courseId: Long): Boolean {
    return transaction {
        StudentCourseAccess
                .select { StudentCourseAccess.student eq studentId and
                        (StudentCourseAccess.course eq courseId) }
                .count() > 0
    }
}

fun isVisibleExerciseOnCourse(courseExId: Long, courseId: Long): Boolean {
    return transaction {
        CourseExercise
                .select {
                    CourseExercise.course eq courseId and
                            (CourseExercise.id eq courseExId) and
                            (CourseExercise.studentVisible eq true)
                }
                .count() > 0
    }
}

