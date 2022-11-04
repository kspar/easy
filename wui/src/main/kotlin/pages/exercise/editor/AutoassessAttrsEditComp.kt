package pages.exercise.editor

import components.form.IntFieldComp
import components.form.SelectComp
import pages.exercise.AutoassessTypes
import plainDstStr
import rip.kspar.ezspa.Component


class AutoassessAttrsEditComp(
    private val containerImage: String?,
    private val maxTime: Int?,
    private val maxMem: Int?,
    private val onTypeChanged: suspend (String?) -> Unit,
    private val onValidChanged: (Boolean) -> Unit,
    parent: Component?
) : Component(parent) {

    private val typeSelect = SelectComp(
        "Automaatkontrolli tüüp", AutoassessTypes.templates.map {
            SelectComp.Option(it.name, it.container, it.container == containerImage)
        }, true, onTypeChanged, this
    )

    private val timeField = IntFieldComp(
        "Lubatud käivitusaeg (s)", true, 1, 60, initialValue = maxTime,
        onValidChange = ::onElementValidChange,
        parent = this
    )

    private val memField = IntFieldComp(
        "Lubatud mälukasutus (MB)", true, 1, 50, initialValue = maxMem,
        onValidChange = ::onElementValidChange,
        parent = this
    )

    // Must be valid when starting editing
    private var isValid = true

    override val children: List<Component>
        get() = listOf(typeSelect, timeField, memField)

    override fun render() = plainDstStr(typeSelect.dstId, timeField.dstId, memField.dstId)

    private fun onElementValidChange(_notUsed: Boolean) {
        val nowValid = timeField.isValid && memField.isValid
        if (isValid != nowValid) {
            isValid = nowValid
            onValidChanged(isValid)
        }
    }

    fun getTime() = timeField.getIntValue()
    fun getMem() = memField.getIntValue()
}