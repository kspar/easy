package pages.exercise_library

import components.BreadcrumbsComp
import components.Crumb
import components.EzCollComp
import debug
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import toEstonianString
import kotlin.js.Date

@JsModule("css-element-queries/src/ElementQueries")
@JsNonModule
external object ElementQueries {
    fun init()
}

class ExerciseLibRootComp(
        dstId: String
) : Component(null, dstId) {


    private lateinit var breadcrumbs: BreadcrumbsComp
    private lateinit var ezcoll: EzCollComp

    override val children: List<Component>
        get() = listOf(breadcrumbs, ezcoll)

    override fun create() = doInPromise {
        breadcrumbs = BreadcrumbsComp(listOf(Crumb("Ülesandekogu")), this)

        val lastModified = Date()
        val robotIcon = "<ez-icon><svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M11.96.24a2.8 2.8 0 00-2.76 2.8 2.8 2.8 0 001.44 2.45v2.9H5.2a3.17 3.17 0 00-3.27 3.1v9.28a3.17 3.17 0 003.27 3.09h13.6a3.17 3.17 0 003.27-3.1v-9.28A3.17 3.17 0 0018.8 8.4h-5.44V5.5a2.8 2.8 0 001.44-2.45A2.8 2.8 0 0012 .24a2.8 2.8 0 00-.04 0zM7.24 11.98a2.8 2.8 0 01.08 0 2.8 2.8 0 011.09.25 2.8 2.8 0 011.43 3.6l-.22.44a2.8 2.8 0 01-3.75.96 2.8 2.8 0 01-1.12-3.72 2.8 2.8 0 012.5-1.53zm9.57 0a2.8 2.8 0 01.03 0 2.8 2.8 0 011.09.26 2.8 2.8 0 011.38 3.7l-.02.05a2.8 2.8 0 01-3.72 1.33 2.8 2.8 0 01-1.35-3.72 2.8 2.8 0 012.6-1.62z\"/></svg></ez-icon>"
        val coursesIcon = "<ez-icon><svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"black\" width=\"18px\" height=\"18px\"><path d=\"M0 0h24v24H0z\" fill=\"none\"/><path d=\"M2 20h20v-4H2v4zm2-3h2v2H4v-2zM2 4v4h20V4H2zm4 3H4V5h2v2zm-4 7h20v-4H2v4zm2-3h2v2H4v-2z\"/></svg></ez-icon>"
        val exPermsIcon = "<ez-icon><svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" fill=\"black\" width=\"18px\" height=\"18px\"><path d=\"M0 0h24v24H0z\" fill=\"none\" fill-rule=\"evenodd\"/><g fill-rule=\"evenodd\"><path d=\"M9 17l3-2.94c-.39-.04-.68-.06-1-.06-2.67 0-8 1.34-8 4v2h9l-3-3zm2-5c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4\"/><path d=\"M15.47 20.5L12 17l1.4-1.41 2.07 2.08 5.13-5.17 1.4 1.41z\"/></g></svg></ez-icon>"
        val addToCourseIcon = "<ez-icon><svg xmlns=\"http://www.w3.org/2000/svg\" height=\"24\" viewBox=\"0 0 24 24\" width=\"24\"><path d=\"M0 0h24v24H0z\" fill=\"none\"/><path d=\"M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z\"/></svg></ez-icon>"

        val items = listOf(
                EzCollComp.Item("0", "1.2 Aasta liblikas", "#!", robotIcon,
                        true,
                        EzCollComp.TopAttr("Viimati muudetud", lastModified.toEstonianString(), lastModified.toEstonianString(), EzCollComp.AttrType.DATETIME, false, EzCollComp.CollMinWidth.W600),
                        listOf(
                                EzCollComp.BottomAttr("ID", "42", "ID 42", EzCollComp.AttrType.STRING, false),
                                EzCollComp.BottomAttr("Kasutusel", "6 kursusel", "$coursesIcon 6 kursusel", EzCollComp.AttrType.STRING, false),
                                EzCollComp.BottomAttr("Mul on lubatud", "vaadata ja muuta", "$exPermsIcon RW", EzCollComp.AttrType.STRING, false),
                                // Tags
                        ),
                        EzCollComp.AttrWidthS.W200, EzCollComp.AttrWidthM.W300, false,
                        listOf(
                                EzCollComp.Action(addToCourseIcon, "Lisa kursusele...", EzCollComp.CollMinWidth.W600,
                                        { debug { it } })
                        )
                )
        )
        ezcoll = EzCollComp(items, this)
    }

    override fun render() = plainDstStr(breadcrumbs.dstId, ezcoll.dstId)

    override fun postRender() {
    }

    override fun renderLoading() = "Loading..."
}