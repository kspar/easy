package pages.exercise_in_library.editor

import components.form.IntFieldComp
import components.form.SelectComp
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import dao.ExerciseDAO
import pages.exercise_in_library.AutoEvalTypes
import rip.kspar.ezspa.Component
import template
import translation.Str


class AutoassessAttrsEditComp(
    private val solutionFileName: String,
    private val solutionFileType: ExerciseDAO.SolutionFileType,
    private val containerImage: String?,
    private val maxTime: Int?,
    private val maxMem: Int?,
    private val onTypeChanged: suspend (String?) -> Unit,
    private val onValidChanged: (Boolean) -> Unit,
    parent: Component?
) : Component(parent) {

    private val fileNameField = StringFieldComp(
        Str.solutionFilename, true, initialValue = solutionFileName,
        constraints = listOf(StringConstraints.Length(1, 100)),
        onValidChange = ::onElementValidChange, parent = this
    )

    private val typeSelect = SelectComp(
        Str.autoassessType, AutoEvalTypes.templates.map {
            SelectComp.Option(it.name, it.container, it.container == containerImage)
        }, true, unconstrainedPosition = true, onOptionChange = onTypeChanged, parent = this
    )

    private val timeField = if (containerImage != null)
        IntFieldComp(
            Str.allowedExecTimeField, true, 1, 60, initialValue = maxTime,
            fieldNameForMessage = Str.value,
            // autofilled from template, should only be edited by user, not inserted
            // but is recreated on type change, so invalid/missing value should be painted on create
            paintRequiredOnCreate = true,
            onValidChange = ::onElementValidChange,
            parent = this
        ) else null

    private val memField = if (containerImage != null)
        IntFieldComp(
            Str.allowedExecMemField, true, 10, 50, initialValue = maxMem,
            fieldNameForMessage = Str.value,
            paintRequiredOnCreate = true,
            onValidChange = ::onElementValidChange,
            parent = this
        ) else null


    override val children: List<Component>
        get() = listOfNotNull(fileNameField, typeSelect, timeField, memField)

    override fun render() = template(
        """
            <ez-exercise-autoeval>
                $fileNameField
                <ez-block-container>
                    <ez-block style="padding-right: 5rem;">
                        <div id="${typeSelect.dstId}"></div>
                    </ez-block>
                    <ez-block style="flex-grow: 0">
                        <ez-block-container>
                            {{#timeDstId}}<ez-block id="{{timeDstId}}" style="width: 19rem; padding-right: 3rem; flex-grow: 0;"></ez-block>{{/timeDstId}}
                            {{#memDstId}}<ez-block id="{{memDstId}}" style="width: 16rem; flex-grow: 0;"></ez-block>{{/memDstId}}
                        </ez-block-container>
                    </ez-block>
                </ez-block-container>
            </ez-exercise-autoeval>
        """.trimIndent(),
        "timeDstId" to timeField?.dstId,
        "memDstId" to memField?.dstId,
    )

    private fun onElementValidChange(_notUsed: Boolean = true) {
        val nowValid = isValid()
        // callback on every field valid change, does produce duplicate callbacks
        onValidChanged(nowValid)
    }

    fun getFileName() = fileNameField.getValue()
    fun getFileType() = solutionFileType
    fun getContainerImage() = typeSelect.getValue()
    fun getTime() = timeField?.getIntValue()
    fun getMem() = memField?.getIntValue()

    fun isValid() = fileNameField.isValid
            && (timeField?.isValid ?: true)
            && (memField?.isValid ?: true)

    fun validateInitial() {
        fileNameField.validateInitial()
        timeField?.validateInitial()
        memField?.validateInitial()
        // If there's no fields, then there's also no automatic callbacks from the fields
        // Note to self: it's a lot easier to *not* recreate form elements because this messes up validation logic
        onElementValidChange()
    }
}