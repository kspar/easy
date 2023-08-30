package pages.exercise_in_library.editor

import components.form.IntFieldComp
import components.form.SelectComp
import pages.exercise_in_library.AutoEvalTypes
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
        }, true, onOptionChange = onTypeChanged, parent = this
    )

    private val timeField = if (containerImage != null)
        IntFieldComp(
            "Lubatud käivitusaeg (s)", true, 1, 60, initialValue = maxTime,
            fieldNameForMessage = "Väärtus",
            // autofilled from template, should only be edited by user, not inserted
            // but is recreated on type change, so invalid/missing value should be painted on create
            paintRequiredOnCreate = true,
            onValidChange = ::onElementValidChange,
            parent = this
        ) else null

    private val memField = if (containerImage != null)
        IntFieldComp(
            "Lubatud mälukasutus (MB)", true, 10, 50, initialValue = maxMem,
            fieldNameForMessage = "Väärtus",
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
        val nowValid = isValid()
        // callback on every field valid change, does produce duplicate callbacks
        onValidChanged(nowValid)
    }

    fun getContainerImage() = typeSelect.getValue()
    fun getTime() = timeField?.getIntValue()
    fun getMem() = memField?.getIntValue()

    fun isValid() = (timeField?.isValid ?: true) && (memField?.isValid ?: true)

    fun validateInitial() {
        timeField?.validateInitial()
        memField?.validateInitial()
        // If there's no fields, then there's also no automatic callbacks from the fields
        // Note to self: it's a lot easier to *not* recreate form elements because this messes up validation logic
        onElementValidChange()
    }
}