package pages.grade_table

import cache.BasicCourseInfo
import components.BreadcrumbsComp
import components.Crumb
import kotlinx.coroutines.await
import pages.Title
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str
import kotlin.js.Promise

class GradeTableRootComponent(
    private val courseId: String,
    dstId: String
) : Component(null, dstId) {

    private lateinit var crumbsComp: BreadcrumbsComp
    private lateinit var cardComp: GradeTableCardComp

    override val children: List<Component>
        get() = listOf(crumbsComp, cardComp)

    override fun create(): Promise<*> = doInPromise {
        val courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle

        crumbsComp = BreadcrumbsComp(
            listOf(
                Crumb.myCourses,
                Crumb(courseTitle, "/courses/$courseId/exercises"),
                Crumb(Str.gradesLabel)
            ), this
        )
        cardComp = GradeTableCardComp(courseId, courseTitle, this)

        Title.update {
            it.pageTitle = Str.gradesLabel
            it.parentPageTitle = courseTitle
        }
    }
}