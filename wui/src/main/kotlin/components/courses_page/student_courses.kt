package components.courses_page

import IdGenerator
import debug
import debugFunStart
import getElemById
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import onVanillaClick
import spa.Component
import kotlin.js.Promise


fun <T> doInPromise(action: suspend () -> T): Promise<T> =
        Promise { resolve, reject ->
            MainScope().launch {
                try {
                    resolve(action())
                } catch (e: Throwable) {
                    reject(e)
                }
            }
        }


fun <T> List<Promise<T>>.all(): Promise<List<T>> =
        Promise.all(this.toTypedArray()).then { it.asList() }


class StudentCourseListComp(dstId: String
) : Component(dstId) {

    @Serializable
    data class Course(val title: String)

    @Serializable
    data class State(val courses: List<Course>)


    private var listItems: List<CourseListItemComp> = emptyList()

    override val children: List<Component>
        get() = listItems

    fun createFromState(state: State): Promise<*> = doInPromise {

    }


    override fun create(): Promise<*> {
        val f1 = debugFunStart("StudentCourseListComp.load")

        return doInPromise {
            /*
            val courses =
                    fetchEms("/student/courses", ReqMethod.GET, successChecker = { http200 }).await()
                            .parseTo(CoursesPage.StudentCourses.serializer()).await()
            */

            listOf(
                    CourseListItemComp(IdGenerator.nextId(), "tiitel1", ::handleChildClick),
                    CourseListItemComp(IdGenerator.nextId(), "tiitel2", ::handleChildClick)
            ).let {
                listItems = it
            }

            children.map { it.create() }.all().await()

            f1?.end()
        }
    }


    override fun render(): String = """
        <div>courses:
        <ez-dst id="${children[0].dstId}"></ez-dst>
        <ez-dst id="${children[1].dstId}"></ez-dst>
        </div>
    """.trimIndent()


    private fun handleChildClick() {
        debug { "Parent got click" }
    }

}


class CourseListItemComp(dstId: String,
                         var title: String,
                         val onClickSomething: () -> Unit
) : Component(dstId) {

    override fun create(): Promise<*> = doInPromise {

    }

    override fun render(): String = """<p>course: $title</p>"""

    override fun init() {
        getElemById(dstId).onVanillaClick(true) {
            title += "."
            build()
            onClickSomething()
        }
    }
}

