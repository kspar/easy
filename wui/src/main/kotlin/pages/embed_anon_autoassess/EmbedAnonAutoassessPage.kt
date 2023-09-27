package pages.embed_anon_autoassess

import CONTENT_CONTAINER_ID
import PageName
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import pages.EasyPage
import pages.exercise_library.createPathChainSuffix
import queries.createQueryString
import queries.getCurrentQueryParamValue
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getHtml

object EmbedAnonAutoassessPage : EasyPage() {
    override val pageName = PageName.EMBED_ANON_AUTOASSESS

    override val pathSchema = "/embed/exercises/{exerciseId}/**"

    override val pageAuth = PageAuth.NONE
    override val isEmbedded = true

    private val exerciseId: String
        get() = parsePathParams()["exerciseId"]

    private var rootComp: EmbedAnonAutoassessRootComp? = null

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        getHtml().addClass("embedded", "light")

        if (getCurrentQueryParamValue("no-border") == null) {
            getHtml().addClass("bordered")
        }

        val showTitle = getCurrentQueryParamValue("no-title") == null
        val submit = getCurrentQueryParamValue("submit") != null
        val showTemplate = getCurrentQueryParamValue("no-template") == null
        val courseId = getCurrentQueryParamValue("course")
        val ceExId = getCurrentQueryParamValue("exercise")
        val titleAlias = getCurrentQueryParamValue("title-alias")
        val dynamicResize = getCurrentQueryParamValue("no-dynamic-resize") == null


        doInPromise {
            rootComp = EmbedAnonAutoassessRootComp(
                exerciseId,
                showTitle = showTitle,
                titleAlias = titleAlias,
                showTemplate = showTemplate,
                dynamicResize = dynamicResize,
                submit = submit,
                courseExerciseLink = if (courseId != null && ceExId != null) EmbedAnonAutoassessRootComp.CourseExercise(
                    courseId,
                    ceExId
                ) else null,
                CONTENT_CONTAINER_ID
            ).also {
                it.createAndBuild().await()
            }
        }
    }

    override fun destruct() {
        super.destruct()
        rootComp?.destroy()
    }

    fun link(
        exerciseId: String, showTitle: Boolean, showBorder: Boolean, showSubmit: Boolean, showTemplate: Boolean,
        dynamicResize: Boolean, titleAlias: String?, linkCourseId: String?, linkCourseExerciseId: String?,
        titleForPath: String?,
    ) = constructPathLink(mapOf("exerciseId" to exerciseId)) +
            createPathChainSuffix(if (titleForPath != null) listOf(titleForPath) else emptyList()) +
            createQueryString(
                params = buildMap {
                    if (!showTitle)
                        set("no-title", null)
                    if (!showBorder)
                        set("no-border", null)
                    if (showSubmit)
                        set("submit", null)
                    if (!showTemplate)
                        set("no-template", null)
                    if (!dynamicResize)
                        set("no-dynamic-resize", null)
                    if (titleAlias != null)
                        set("title-alias", titleAlias)
                    if (linkCourseId != null)
                        set("course", linkCourseId)
                    if (linkCourseExerciseId != null)
                        set("exercise", linkCourseExerciseId)
                }
            )
}
