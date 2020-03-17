package pages.course_exercises

import doInPromise
import spa.Component
import kotlin.js.Promise

class StudentCourseExercisesListComp(
        private val courseId: String,
        parent: Component?
) : Component(parent) {

    private lateinit var items: List<StudentCourseExercisesItemComp>

    override val children: List<Component>
        get() = items

    override fun create(): Promise<*> = doInPromise {

    }

    override fun render(): String = """list"""

}

class StudentCourseExercisesItemComp(
        parent: Component?
) : Component(parent) {

    override fun render(): String = "item"
}

