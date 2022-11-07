package pages.exercise.editor

import rip.kspar.ezspa.Component
import tmRender


class AutoassessAttrsReadComp(
    private val type: String,
    private val maxTime: Int?,
    private val maxMem: Int?,
    parent: Component?
) : Component(parent) {

    override fun render() = tmRender(
        "t-c-exercise-tab-aa-attrs-read",
        "typeLabel" to "Tüüp",
        "type" to type,
        "maxTimeLabel" to "Lubatud käivitusaeg",
        "maxTime" to if (maxTime != null) "$maxTime s" else null,
        "maxMemLabel" to "Lubatud mälukasutus",
        "maxMem" to if (maxMem != null) "$maxMem MB" else null,
    )
}