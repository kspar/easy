package pages.exercise_in_library.editor.tsl.sections

import Icons
import components.form.OldButtonComp
import components.form.CodeFieldComp
import components.form.StringFieldComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import show
import template
import translation.Str
import tsl.common.model.ReturnValueCheck

class TSLReturnCheckSection(
    private var check: ReturnValueCheck?,
    private val onUpdate: () -> Unit,
    private val onValidChanged: () -> Unit,
    parent: Component,
) : Component(parent) {

    private var showField: Boolean = check != null

    private var showBtn: OldButtonComp? = null
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
                helpText = Str.tslReturnCheckValueHelp,
                onValueChange = { onUpdate() },
                parent = this
            )

            passMsg = StringFieldComp(
                "", true,
                fieldNameForMessage = Str.feedback,
                initialValue = check?.passedMessage ?: Str.tslReturnCheckPass,
                onValidChange = { onValidChanged() },
                onValueChange = { onUpdate() },
                parent = this
            )
            failMsg = StringFieldComp(
                "", true,
                fieldNameForMessage = Str.feedback,
                initialValue = check?.failedMessage
                    ?: Str.tslReturnCheckFail,
                onValidChange = { onValidChanged() },
                onValueChange = { onUpdate() },
                parent = this
            )

            showBtn = null
        } else {
            returnField = null
            passMsg = null
            failMsg = null
            showBtn = OldButtonComp(
                OldButtonComp.Type.FLAT, Str.tslReturnCheck, Icons.add, ::showSection, parent = this
            )
        }
    }

    override fun render() = template(
        """
            {{#showField}}
                <fieldset class='ez-tsl-check'>
                    <ez-flex style='margin-top: 1.5rem'>{{returnCheckMsg}}</ez-flex>
                    $returnField
                    <ez-flex style='color: var(--ez-text-inactive);'>
                        <ez-inline-flex style='margin-right: 1rem; margin-bottom: .8rem;'>${Icons.check}</ez-inline-flex>
                        <ez-tsl-feedback-msg style='flex-grow: 1;'>$passMsg</ez-tsl-feedback-msg>
                    </ez-flex>
                    <ez-flex style='color: var(--ez-text-inactive);'>
                        <ez-inline-flex style='margin-right: 1rem; margin-bottom: .8rem;'>${Icons.close}</ez-inline-flex>
                        <ez-tsl-feedback-msg style='flex-grow: 1;'>$failMsg</ez-tsl-feedback-msg>
                    </ez-flex>
                </fieldset>
            {{/showField}}
            {{^showField}}
                <ez-tsl-add-button id='${showBtn?.dstId}'></ez-tsl-add-button>
            {{/showField}}
        """.trimIndent(),
        "returnCheckMsg" to Str.tslReturnCheckPrefixMsg,
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
