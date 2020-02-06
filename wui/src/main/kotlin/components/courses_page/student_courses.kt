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
import spa.CacheableComponent
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
) : CacheableComponent<StudentCourseListComp.State>(dstId) {

    @Serializable
    data class Course(val title: String)

    @Serializable
    data class State(val courses: List<Course>, val itemStates: List<CourseListItemComp.State>)


    private var listItems: List<CourseListItemComp> = emptyList()

    override val children: List<Component>
        get() = listItems

    override fun createFromState(state: State): Promise<*> = doInPromise {
        val items = state.courses.map {
            CourseListItemComp(IdGenerator.nextId(), it.title, ::handleChildClick)
        }

        listItems = items

        listItems.zip(state.itemStates).map { (item, state) ->
            item.createFromState(state)
        }.all().await()
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

            listItems.map { it.create() }.all().await()

            f1?.end()
        }
    }


    override fun render(): String = """
        <div>courses:
        <ez-dst id="${children[0].dstId}"></ez-dst>
        <ez-dst id="${children[1].dstId}"></ez-dst>
        </div>
    """.trimIndent()

    override fun getCacheableState(): State {
        return State(listItems.map { Course(it.title) }, listItems.map { it.getCacheableState() })
    }

    private fun handleChildClick() {
        debug { "Parent got click" }
    }

}


class CourseListItemComp(dstId: String,
                         var title: String,
                         val onClickSomething: () -> Unit
) : CacheableComponent<CourseListItemComp.State>(dstId) {

    @Serializable
    data class State(val number: Int)

    private var number: Int = 0

    override fun create(): Promise<*> = doInPromise {
        debug { "List item created without state" }
        number = 42
    }

    override fun createFromState(state: State): Promise<*> = doInPromise {
        debug { "List item created from state $state" }
        number = state.number
    }

    override fun render(): String = """<p>course: $title</p>"""

    override fun init() {
        getElemById(dstId).onVanillaClick(true) {
            title += "."
            build()
            onClickSomething()
        }
    }

    override fun getCacheableState(): State {
        debug { "Caching number $number" }
        return State(number)
    }
}

