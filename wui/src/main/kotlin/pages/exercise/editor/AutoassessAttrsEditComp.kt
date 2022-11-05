package pages.exercise.editor

import components.form.IntFieldComp
import components.form.SelectComp
import pages.exercise.AutoEvalTypes
import rip.kspar.ezspa.Component
import tmRender


class AutoassessAttrsEditComp(
    private val containerImage: String?,
    private val maxTime: Int?,
    private val maxMem: Int?,
    private val onTypeChanged: suspend (String?) -> Unit,
    private val onValidChanged: (Boolean) -> Unit,
    parent: Component?
) : Component(parent) {

    private val typeSelect = SelectComp(
        "Automaatkontrolli tüüp", AutoEvalTypes.templates.map {
            SelectComp.Option(it.name, it.container, it.container == containerImage)
        }, true, onTypeChanged, this
    )

    private val timeField = if (containerImage != null)
        IntFieldComp(
            "Lubatud käivitusaeg (s)", true, 1, 60, initialValue = maxTime,
            // autofilled from template, should only be edited by user, not inserted
            // but is recreated on type change, so invalid/missing value should be painted on create
            paintRequiredOnCreate = true,
            onValidChange = ::onElementValidChange,
            parent = this
        ) else null

    private val memField = if (containerImage != null)
        IntFieldComp(
            "Lubatud mälukasutus (MB)", true, 1, 50, initialValue = maxMem,
            paintRequiredOnCreate = true,
            onValidChange = ::onElementValidChange,
            parent = this
        ) else null


    override val children: List<Component>
        get() = listOfNotNull(typeSelect, timeField, memField)

    override fun render() = tmRender(
        "t-c-exercise-tab-aa-attrs-edit",
        "typeDstId" to typeSelect.dstId,
        "timeDstId" to timeField?.dstId,
        "memDstId" to memField?.dstId
    )

    private fun onElementValidChange(_notUsed: Boolean = true) {
        val nowValid = (timeField?.isValid ?: true) && (memField?.isValid ?: true)
        // callback on every field valid change, does produce duplicate callbacks
        onValidChanged(nowValid)
    }

    fun getContainerImage() = typeSelect.getValue()
    fun getTime() = timeField?.getIntValue()
    fun getMem() = memField?.getIntValue()

    fun validateInitial() {
        timeField?.validateInitial()
        memField?.validateInitial()
        // If there's no fields, then there's also no automatic callbacks from the fields
        onElementValidChange()
    }
}