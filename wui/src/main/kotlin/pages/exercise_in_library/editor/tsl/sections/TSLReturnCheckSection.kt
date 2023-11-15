package pages.exercise_in_library.editor.tsl.sections

import Icons
import components.form.ButtonComp
import components.form.CodeFieldComp
import components.form.StringFieldComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import show
import template
import tsl.common.model.ReturnValueCheck

class TSLReturnCheckSection(
    private var check: ReturnValueCheck?,
    private val onUpdate: () -> Unit,
    private val onValidChanged: () -> Unit,
    parent: Component,
) : Component(parent) {

    private var showField: Boolean = check != null

    private var showBtn: ButtonComp? = null
    private var returnField: CodeFieldComp? = null
    private var passMsg: StringFieldComp? = null
    private var failMsg: StringFieldComp? = null


    override val children: List<Component>
        get() = listOfNotNull(showBtn, returnField, passMsg, failMsg)

    override fun create() = doInPromise {
        if (showField) {
            returnField = CodeFieldComp(
                isRequired = false,
                fileExtension = "py",
                initialValue = check?.returnValue.orEmpty(),
                helpText = "Oodatav funktsiooni tagastusväärtus Pythoni süntaksis",
                onValueChange = { onUpdate() },
                parent = this
            )

            passMsg = StringFieldComp(
                "", true,
                helpText = """Tagasiside õnnestumisel, nt "Leidsin väljundist õige vastuse 42"""",
                fieldNameForMessage = "Tagasiside",
                initialValue = check?.passedMessage ?: "Funktsioon tagastas õige väärtuse {expected}",
                onValidChange = { onValidChanged() },
                onValueChange = { onUpdate() },
                parent = this
            )
            failMsg = StringFieldComp(
                "", true,
                helpText = """Tagasiside ebaõnnestumisel, nt "Ei leidnud programmi väljundist oodatud tulemust 42"""",
                fieldNameForMessage = "Tagasiside",
                initialValue = check?.failedMessage
                    ?: "Ootasin, et funktsioon tagastaks {expected}, aga tagastas {actual}",
                onValidChange = { onValidChanged() },
                onValueChange = { onUpdate() },
                parent = this
            )

            showBtn = null
        } else {
            returnField = null
            passMsg = null
            failMsg = null
            showBtn = ButtonComp(
                ButtonComp.Type.FLAT, "Tagastusväärtuse kontroll", Icons.add, ::showSection, parent = this
            )
        }
    }

    override fun render() = template(
        """
            {{#showField}}
                <fieldset class='ez-tsl-check'>
                    <ez-flex style='margin-top: 1.5rem'>Tagastusväärtus peab olema:</ez-flex>
                    $returnField
                    $passMsg
                    $failMsg
                </fieldset>
            {{/showField}}
            {{^showField}}
                <ez-tsl-add-button id='${showBtn?.dstId}'></ez-tsl-add-button>
            {{/showField}}
        """.trimIndent(),
        "showField" to showField,
    )

    fun getCheck(): ReturnValueCheck? {
        val r = getReturnValue()
        return if (r.isNullOrBlank())
            null
        else
            ReturnValueCheck(
                r,
                "",
                passMsg!!.getValue(),
                failMsg!!.getValue(),
            )
    }

    fun setEditable(nowEditable: Boolean) {
        showBtn?.show(nowEditable)
        returnField?.isDisabled = !nowEditable
        passMsg?.isDisabled = !nowEditable
        failMsg?.isDisabled = !nowEditable
    }

    fun isValid() = passMsg?.isValid ?: true &&
            failMsg?.isValid ?: true

    private fun getReturnValue() = returnField?.getValue()

    private suspend fun showSection() {
        showField = true
        createAndBuild().await()
    }
}
