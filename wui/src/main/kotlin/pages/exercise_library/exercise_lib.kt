package pages.exercise_library

import Icons
import components.BreadcrumbsComp
import components.Crumb
import components.EzCollComp
import debug
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import toEstonianString
import kotlin.js.Date

//@JsModule("css-element-queries/src/ElementQueries")
//@JsNonModule
//external object ElementQueries {
//    fun init()
//    fun listen()
//}

class ExerciseLibRootComp(
        dstId: String
) : Component(null, dstId) {


    private lateinit var breadcrumbs: BreadcrumbsComp
    private lateinit var ezcoll: EzCollComp<Unit>

    override val children: List<Component>
        get() = listOf(breadcrumbs, ezcoll)

    override fun create() = doInPromise {
        breadcrumbs = BreadcrumbsComp(listOf(Crumb("Ülesandekogu")), this)

        val lastModified = Date()

//        val items = listOf(
//                EzCollComp.Item("0", "1.2 Aasta liblikas", EzCollComp.TitleStatus.NORMAL, "#!", Icons.robot,
//                        true,
//                        EzCollComp.TopAttr("Viimati muudetud", lastModified.toEstonianString(), lastModified.toEstonianString()),
//                        listOf(
//                                EzCollComp.BottomAttr("ID", "42", "ID 42"),
//                                EzCollComp.BottomAttr("Kasutusel", "6 kursusel", "${Icons.courses} 6 kursusel"),
//                                EzCollComp.BottomAttr("Mul on lubatud", "vaadata ja muuta", "${Icons.exercisePermissions} RW"),
//                                // Tags
//                        ), false,
//                        listOf(
//                                EzCollComp.Action(Icons.addToCourse, "Lisa kursusele...",
//                                        { debug { it }; it }, true
//                                )
//                        ), Unit, EzCollComp.AttrWidthS.W200, EzCollComp.AttrWidthM.W300, false
//                )
//        )
//        ezcoll = EzCollComp(items, EzCollComp.Strings("ülesanne", "ülesannet"), parent = this)
    }

    override fun render() = plainDstStr(breadcrumbs.dstId, ezcoll.dstId)

    override fun postRender() {
    }

    override fun renderLoading() = "Loading..."
}