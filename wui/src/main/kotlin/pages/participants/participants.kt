package pages.participants

import components.BreadcrumbsComp
import components.Crumb
import kotlinx.coroutines.await
import plainDstStr
import cache.BasicCourseInfo
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

// buildList is experimental
@ExperimentalStdlibApi
class ParticipantsRootComp(
    private val courseId: String,
    dstId: String
) : Component(null, dstId) {

    private lateinit var breadcrumbs: BreadcrumbsComp
    private lateinit var teachersList: ParticipantsTeachersListComp
    private lateinit var studentsList: ParticipantsStudentsListComp
    private lateinit var courseTitle: String

    override val children: List<Component>
        get() = listOf(breadcrumbs, teachersList, studentsList)

    override fun create() = doInPromise {
        courseTitle = BasicCourseInfo.get(courseId).await().title
        breadcrumbs = BreadcrumbsComp(
            listOf(
                Crumb.myCourses,
                Crumb.courseExercises(courseId, courseTitle),
                Crumb("Osalejad")
            ), this
        )

        teachersList = ParticipantsTeachersListComp(courseId, this)
        studentsList = ParticipantsStudentsListComp(courseId, this)
    }

    // TODO: template with course title
    override fun render(): String = plainDstStr(breadcrumbs.dstId, teachersList.dstId, studentsList.dstId)

}