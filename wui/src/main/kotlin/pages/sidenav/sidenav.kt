package pages.sidenav

import Auth
import Role
import debug
import kotlinx.coroutines.await
import libheaders.MSidenavInstance
import libheaders.Materialize
import pages.about.AboutPage
import pages.terms.TermsProxyPage
import rip.kspar.ezspa.*
import template
import translation.Str
import kotlin.js.Promise

enum class ActivePage {
    MY_COURSES, COURSE_EXERCISES, COURSE_GRADES, COURSE_PARTICIPANTS,
    LIBRARY,
    ARTICLES,
    STUDENT_EXERCISE,
}

object Sidenav {

    data class Spec(
        val courseId: String? = null,
        val activePage: ActivePage? = null,
        val pageSection: PageSection? = null,
    )

    data class PageSection(
        val title: String,
        val items: List<Item>,
    )

    sealed class Item(
        open val iconHtml: String,
        open val text: String,
        val id: String = IdGenerator.nextId(),
    )

    data class Link(
        override val iconHtml: String,
        override val text: String,
        val href: String,
    ) : Item(iconHtml, text) {
        override fun toString() = "Link(text=$text, href=$href)"
    }

    data class Action(
        override val iconHtml: String,
        override val text: String,
        val onActivate: suspend (a: Action) -> Unit,
    ) : Item(iconHtml, text) {
        override fun toString() = "Action(text=$text)"
    }


    private const val DST_ID = "sidenav-wrap"

    private lateinit var sidenavComp: SidenavRootComp

    suspend fun build() {
        sidenavComp = SidenavRootComp(
            if (Auth.authenticated) Auth.activeRole else Role.STUDENT,
            Auth.getAvailableRoles(),
            DST_ID
        )
        sidenavComp.createAndBuild().await()
    }

    fun refresh(spec: Spec, forceUpdateCourse: Boolean = false) {
        doInPromise {
            // TODO: test, is this necessary?
            sidenavComp.updateRole(if (Auth.authenticated) Auth.activeRole else Role.STUDENT).await()

            // Updating the course section takes time but active page should change ASAP,
            // so let's update it now and later again after rebuild
            if (forceUpdateCourse)
                sidenavComp.updateActivePage(spec.activePage).await()

            sidenavComp.updateCourse(spec.courseId, forceUpdateCourse).await()
            sidenavComp.updateActivePage(spec.activePage).await()
            sidenavComp.updatePageItems(spec.pageSection).await()
        }
    }

    fun replacePageSection(pageSection: PageSection?): Promise<Unit> {
        return sidenavComp.updatePageItems(pageSection)
    }
}


class SidenavRootComp(
    private var activeRole: Role,
    private val availableRoles: List<Role>,
    dstId: String
) : Component(null, dstId) {

    private var headSectionComp = SidenavHeadAccountSection(
        "${Auth.firstName ?: ""} ${Auth.lastName ?: ""}",
        Auth.email ?: "",
        activeRole, availableRoles, ::triggerRoleChange, this
    )

    private var generalSectionComp = SidenavGeneralSectionComp(activeRole, this, IdGenerator.nextId())

    private var courseSectionComp: SidenavCourseSectionComp? = null
    private val courseSectionDstId = IdGenerator.nextId()

    private var pageSectionComp: SidenavPageSectionComp? = null
    private val pageSectionDstId = IdGenerator.nextId()

    private var trailerSectionComp = SidenavTrailerSectionComp(activeRole, this, IdGenerator.nextId())

    private lateinit var mSidenavInstance: MSidenavInstance

    private var courseId: String? = null
    private var activePage: ActivePage? = null
    private var pageSection: Sidenav.PageSection? = null

    override val children: List<Component>
        get() = listOfNotNull(
            headSectionComp,
            generalSectionComp,
            courseSectionComp,
            pageSectionComp,
            trailerSectionComp
        )

    override fun render(): String = template(
        """
            <ul id="sidenav" class="sidenav sidenav-fixed">
            $headSectionComp
            $generalSectionComp
                <ez-dst id="$courseSectionDstId"></ez-dst>
                <ez-dst id="$pageSectionDstId"></ez-dst>
                <ez-dst id="${trailerSectionComp.dstId}" class="trailer"></ez-dst>
                <ez-sidenav-footer>
                    <a href=${AboutPage.link()}>{{about}}</a> · <a href='${TermsProxyPage.link()}' target="_blank">{{tos}}</a>
                </ez-sidenav-footer>
            </ul>
        """.trimIndent(),
        "tos" to Str.linkTOS,
        "about" to Str.linkAbout,
    )

    override fun postRender() {
        mSidenavInstance = Materialize.Sidenav.init(
            getElemById("sidenav"), objOf()
        )
    }

    fun updateRole(newRole: Role) = doInPromise {
        if (activeRole != newRole) {
            debug { "Sidenav updating based on new role" }
            activeRole = newRole

            generalSectionComp.destroy()
            generalSectionComp = SidenavGeneralSectionComp(activeRole, this, generalSectionComp.dstId)

            courseId?.let { courseId ->
                courseSectionComp?.destroy()
                courseSectionComp = SidenavCourseSectionComp(activeRole, courseId, this, courseSectionDstId)
            }

            pageSection?.let { pageSection ->
                pageSectionComp?.destroy()
                pageSectionComp = SidenavPageSectionComp(pageSection, this, pageSectionDstId)
            }

            trailerSectionComp.destroy()
            trailerSectionComp = SidenavTrailerSectionComp(activeRole, this, trailerSectionComp.dstId)

            listOfNotNull(
                generalSectionComp.createAndBuild(),
                courseSectionComp?.createAndBuild(),
                pageSectionComp?.createAndBuild(),
                trailerSectionComp.createAndBuild()
            ).unionPromise().await()

            // Force refresh active page since the sidenav was rebuilt
            refreshActivePage()
        }
    }

    fun updateCourse(newCourseId: String?, force: Boolean) = doInPromise {
        if (courseId != newCourseId || force) {
            debug { "Sidenav updating course section" }
            courseId = newCourseId

            when {
                newCourseId != null -> {
                    val comp = SidenavCourseSectionComp(activeRole, newCourseId, this, courseSectionDstId)
                    // Recreate without destroying i.e. visually the content will change in-place without clearing first
                    courseSectionComp = comp
                    comp.createAndBuild().await()
                }

                else -> {
                    courseSectionComp?.destroy()
                    courseSectionComp = null
                }
            }
        }
    }

    fun updateActivePage(newActivePage: ActivePage?) = doInPromise {
        activePage = newActivePage
        refreshActivePage()
    }

    private fun refreshActivePage() {
        generalSectionComp.clearAndSetActivePage(activePage)
        courseSectionComp?.clearAndSetActivePage(activePage)
    }

    fun updatePageItems(newPageSection: Sidenav.PageSection?) = doInPromise {
        if (pageSection != newPageSection) {
            debug { "Sidenav updating page section" }
            pageSection = newPageSection

            when {
                newPageSection != null -> {
                    val comp = SidenavPageSectionComp(newPageSection, this, pageSectionDstId)
                    pageSectionComp?.destroy()
                    pageSectionComp = comp
                    comp.createAndBuild().await()
                }

                else -> {
                    pageSectionComp?.destroy()
                    pageSectionComp = null
                }
            }
        }
    }

    private fun triggerRoleChange(newRole: Role) {
        Auth.switchToRole(newRole)
        EzSpa.PageManager.updatePage()
    }
}
