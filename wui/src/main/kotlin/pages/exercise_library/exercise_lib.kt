package pages.exercise_library

import components.EzCollComp
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import toEstonianString
import kotlin.js.Date

class ExerciseLibRootComp(
        dstId: String
) : Component(null, dstId) {


    private lateinit var ezcoll: EzCollComp

    override val children: List<Component>
        get() = listOf(ezcoll)

    override fun create() = doInPromise {
        val items = listOf(
                EzCollComp.Item("title", "#!", "42",
                        EzCollComp.TopAttr("Tähtaeg", Date().toEstonianString(), "", EzCollComp.AttrType.DATETIME, true, EzCollComp.CollMinWidth.W600),
                        listOf(EzCollComp.BottomAttr("k", "v", "@ v", EzCollComp.AttrType.STRING, false, EzCollComp.AttrMinWidth.W200)),
                        listOf(EzCollComp.Action(null, "Peida", EzCollComp.CollMinWidth.W600)))
        )
        ezcoll = EzCollComp(items, this)
    }

    override fun render() = plainDstStr(ezcoll.dstId)

    override fun postRender() {
    }

    override fun renderLoading() = "Loading..."
}