package pages.exercise_in_library.editor.tsl.sections

import Icons
import components.form.*
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import show
import template
import tsl.common.model.CheckType
import tsl.common.model.DataCategory
import tsl.common.model.GenericCheck

class TSLDataChecksSection(
    private var checks: MutableList<GenericCheck>,
    private val onUpdate: () -> Unit,
    private val onValidChanged: () -> Unit,
    parent: Component,
) : Component(parent) {

    data class CheckSection(
        val id: Long,
        val value: TextFieldComp, val type: SelectComp, val comparison: SelectComp,
//        val nothingElse: CheckboxComp,
        val ordered: CheckboxComp,
        val passMsg: StringFieldComp, val failMsg: StringFieldComp,
        val upBtn: IconButtonComp, val downBtn: IconButtonComp, val deleteBtn: IconButtonComp,
    )

    private lateinit var sections: List<CheckSection>

    private val addCheckBtn =
        ButtonComp(ButtonComp.Type.FLAT, "Väljundi kontroll", Icons.add, ::addCheck, parent = this)

    override val children: List<Component>
        get() = listOf(addCheckBtn) + sections.flatMap {
            listOf(
                it.value, it.type, it.comparison, it.ordered,
                it.passMsg, it.failMsg,
                it.upBtn, it.downBtn, it.deleteBtn
            )
        }

    override fun create() = doInPromise {
        sections = checks.mapIndexed { i, check ->
            CheckSection(
                check.id,
                TextFieldComp(
                    "", false, "",
                    helpText = "Oodatavad õpilase programmi väljundid, iga väärtus eraldi real",
                    startActive = true,
                    initialValue = check.expectedValue.joinToString("\n"),
                    onValueChange = { onUpdate() },
                    onValidChange = { onValidChanged() },
                    parent = this
                ),
                SelectComp(
                    options = listOf(
                        SelectComp.Option(
                            "leiduvad kõik",
                            CheckType.ALL_OF_THESE.name,
                            check.checkType == CheckType.ALL_OF_THESE
                        ),
                        SelectComp.Option(
                            "leidub vähemalt üks",
                            CheckType.ANY_OF_THESE.name,
                            check.checkType == CheckType.ANY_OF_THESE
                        ),
                        SelectComp.Option(
                            "ei leidu vähemalt ühte",
                            CheckType.MISSING_AT_LEAST_ONE_OF_THESE.name,
                            check.checkType == CheckType.MISSING_AT_LEAST_ONE_OF_THESE
                        ),
                        SelectComp.Option(
                            "ei leidu mitte ühtegi",
                            CheckType.NONE_OF_THESE.name,
                            check.checkType == CheckType.NONE_OF_THESE
                        ),
                    ),
                    onOptionChange = { onUpdate() },
                    parent = this
                ),
                SelectComp(
                    options = listOf(
                        SelectComp.Option(
                            "sõnedest",
                            DataCategory.CONTAINS_STRINGS.name,
                            check.dataCategory == DataCategory.CONTAINS_STRINGS
                        ),
                        SelectComp.Option(
                            "arvudest",
                            DataCategory.CONTAINS_NUMBERS.name,
                            check.dataCategory == DataCategory.CONTAINS_NUMBERS
                        ),
                        SelectComp.Option(
                            "ridadest",
                            DataCategory.CONTAINS_LINES.name,
                            check.dataCategory == DataCategory.CONTAINS_LINES
                        ),
                    ),
                    onOptionChange = { onUpdate() },
                    parent = this
                ),
                CheckboxComp(
                    "Väärtuste järjekord peab oleme sama",
                    initialValue = check.elementsOrdered ?: false,
                    onValueChange = { onUpdate() },
                    parent = this
                ),
                StringFieldComp(
                    "", true,
                    helpText = """Tagasiside õnnestumisel, nt "Leidsin väljundist õige vastuse 42"""",
                    fieldNameForMessage = "Tagasiside",
                    initialValue = check.passedMessage,
                    onValidChange = { onValidChanged() },
                    onValueChange = { onUpdate() },
                    parent = this
                ),
                StringFieldComp(
                    "", true,
                    helpText = """Tagasiside ebaõnnestumisel, nt "Ei leidnud programmi väljundist oodatud tulemust 42"""",
                    fieldNameForMessage = "Tagasiside",
                    initialValue = check.failedMessage,
                    onValidChange = { onValidChanged() },
                    onValueChange = { onUpdate() },
                    parent = this
                ),
                IconButtonComp(
                    Icons.arrowUp, "Liiguta üles", {
                        updateAndGetChecks()
                        val currentModel = checks.first { it.id == check.id }
                        val currentIdx = checks.indexOf(currentModel)
                        checks.remove(currentModel)
                        checks.add(currentIdx - 1, currentModel)
                        createAndBuild().await()
                        onUpdate()
                    },
                    isEnabledInitial = i != 0,
                    parent = this
                ),
                IconButtonComp(
                    Icons.arrowDown, "Liiguta alla", {
                        updateAndGetChecks()
                        val currentModel = checks.first { it.id == check.id }
                        val currentIdx = checks.indexOf(currentModel)
                        checks.remove(currentModel)
                        checks.add(currentIdx + 1, currentModel)
                        createAndBuild().await()
                        onUpdate()
                    },
                    isEnabledInitial = i != checks.size - 1,
                    parent = this
                ),
                IconButtonComp(Icons.delete, "Kustuta", {
                    updateAndGetChecks()
                    checks.removeAll { it.id == check.id }
                    createAndBuild().await()
                    onUpdate()
                }, parent = this),
            )
        }
    }

    override fun render() = template(
        """
            {{#checks}}
                <fieldset style='border: 1px solid #ccc; border-radius: .5rem; color: var(--ez-text-inactive); margin-top: 3rem;'>
                <legend>
                    <ez-dst id='{{titleDst}}'></ez-dst>
                    <ez-dst id='{{upBtnDst}}'></ez-dst>
                    <ez-dst id='{{downBtnDst}}'></ez-dst>
                    <ez-dst id='{{deleteBtnDst}}'></ez-dst>
                </legend>
                    Väljundis <ez-tsl-inline-field id='{{typeDst}}' style='width: 20rem;'></ez-tsl-inline-field> 
                    järgmistest <ez-tsl-inline-field id='{{comparisonDst}}' style='width: 13rem;'></ez-tsl-inline-field>: 
                    <ez-tsl-field-text-value id='{{valueDst}}'></ez-tsl-field-text-value>
                    <ez-dst id='{{okMsgDst}}'></ez-dst>
                    <ez-dst id='{{nokMsgDst}}'></ez-dst>
                    <!-- TODO: "more options" expand -->
                    <ez-dst style='display: none;' id='{{orderDst}}'></ez-dst>
                </fieldset>
            {{/checks}}
            <ez-tsl-add-button id='{{addBtnDst}}'></ez-tsl-add-button>
        """.trimIndent(),
        "checks" to sections.map {
            mapOf(
                "typeDst" to it.type.dstId,
                "comparisonDst" to it.comparison.dstId,
                "valueDst" to it.value.dstId,
                "orderDst" to it.ordered.dstId,
                "okMsgDst" to it.passMsg.dstId,
                "nokMsgDst" to it.failMsg.dstId,
                "upBtnDst" to it.upBtn.dstId,
                "downBtnDst" to it.downBtn.dstId,
                "deleteBtnDst" to it.deleteBtn.dstId,
            )
        },
        "addBtnDst" to addCheckBtn.dstId,
    )

    fun updateAndGetChecks(): List<GenericCheck> {
        checks = sections.map {
            GenericCheck(
                id = it.id,
                checkType = CheckType.valueOf(it.type.getValue()!!),
                dataCategory = DataCategory.valueOf(it.comparison.getValue()!!),
                expectedValue = it.value.getValue().split("\n").filter(String::isNotBlank),
                elementsOrdered = it.ordered.isChecked,
                beforeMessage = "",
                passedMessage = it.passMsg.getValue(),
                failedMessage = it.failMsg.getValue(),
            )
        }.toMutableList()
        return checks
    }

    private suspend fun addCheck() {
        updateAndGetChecks()
        checks.add(
            GenericCheck(
                id = IdGenerator.nextLongId(),
                checkType = CheckType.ALL_OF_THESE,
                dataCategory = DataCategory.CONTAINS_STRINGS,
                expectedValue = listOf(),
                elementsOrdered = false,
                beforeMessage = "Väljundi kontroll",
                passedMessage = "OK",
                failedMessage = "Viga"
            )
        )
        createAndBuild().await()
        onUpdate()
    }

    fun setEditable(nowEditable: Boolean) {
        sections.forEach {
            it.type.isDisabled = !nowEditable
            it.comparison.isDisabled = !nowEditable
            it.value.isDisabled = !nowEditable
            it.ordered.isDisabled = !nowEditable
            it.passMsg.isDisabled = !nowEditable
            it.failMsg.isDisabled = !nowEditable
        }
        rebuild()
        sections.forEach {
            it.upBtn.show(nowEditable)
            it.downBtn.show(nowEditable)
            it.deleteBtn.show(nowEditable)
        }
        addCheckBtn.show(nowEditable)
    }

    fun isValid() = sections.all {
        it.value.isValid &&
                it.passMsg.isValid &&
                it.failMsg.isValid
    }
}
