package pages.about

import PageName
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import pages.EasyPage
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import queries.createQueryString
import queries.getCurrentQueryParamValue
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getHtml

object SimilarityAnalysisPage : EasyPage() {
    override val pageName = PageName.SIMILARITY_ANALYSIS

    override val pathSchema = "/similarity"

    override val courseId
        get() = getCurrentQueryParamValue("course")!!

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(courseId, ActivePage.COURSE_SIMILARITY_ANALYSIS)

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        getHtml().addClass("wui3")

        val courseId = getCurrentQueryParamValue("course")!!
        val exerciseId = getCurrentQueryParamValue("exercise")

        doInPromise {
            SimilarityComp(courseId, exerciseId).createAndBuild().await()
        }
    }

    override fun destruct() {
        super.destruct()
        getHtml().removeClass("wui3")
    }

    fun link(courseId: String? = null, exerciseId: String? = null) =
        constructPathLink(emptyMap()) +
                createQueryString(params = buildMap {
                    if (courseId != null) put("course", courseId)
                    if (exerciseId != null) put("exercise", exerciseId)
                })
}

