package pages.grade_table

import CONTENT_CONTAINER_ID
import PageName
import Role
import kotlinx.coroutines.await
import pages.EasyPage
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import rip.kspar.ezspa.doInPromise


object GradeTablePage : EasyPage() {

    override val pageName: Any
        get() = PageName.GRADE_TABLE

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(courseId, ActivePage.COURSE_GRADES)

    override val allowedRoles: List<Role>
        get() = listOf(Role.TEACHER, Role.ADMIN)

    override val pathSchema = "/courses/{courseId}/grades"

    override val courseId: String
        get() = parsePathParams()["courseId"]

    private var rootComp: GradeTableRootComponent? = null

    override fun build(pageStateStr: String?) {
        doInPromise {
            super.build(pageStateStr)
            val root = GradeTableRootComponent(courseId, CONTENT_CONTAINER_ID)
            rootComp = root
            root.createAndBuild().await()
        }
    }

    override fun destruct() {
        super.destruct()
        rootComp?.destroy()
    }

    fun link(courseId: String) = constructPathLink(mapOf("courseId" to courseId))
}

