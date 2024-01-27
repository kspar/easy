package pages.course_exercise.teacher

import CONTENT_CONTAINER_ID
import rip.kspar.ezspa.Component

class TeacherCourseExerciseComp(
    private val courseId: String,
    private val courseExId: String,
    private val setPathSuffix: (String) -> Unit
) : Component(null, CONTENT_CONTAINER_ID) {



}