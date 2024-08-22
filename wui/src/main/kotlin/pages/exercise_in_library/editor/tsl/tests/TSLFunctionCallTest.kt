package pages.exercise_in_library.editor.tsl.tests

import Icons
import components.form.CodeFieldComp
import pages.exercise_in_library.editor.tsl.TSLTestComponent
import pages.exercise_in_library.editor.tsl.sections.TSLDataChecksSection
import pages.exercise_in_library.editor.tsl.sections.TSLInputFilesSection
import pages.exercise_in_library.editor.tsl.sections.TSLReturnCheckSection
import pages.exercise_in_library.editor.tsl.sections.TSLStdInSection
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import template
import translation.Str
import tsl.common.model.FileData
import tsl.common.model.FunctionExecutionTest
import tsl.common.model.FunctionType
import tsl.common.model.Test

class TSLFunctionCallTest(
    private val initialModel: FunctionExecutionTest?,
    private val onUpdate: suspend () -> Unit,
    private val onValidChanged: () -> Unit,
    parent: Component,
    dstId: String,
) : TSLTestComponent(parent, dstId) {

    private val testId = initialModel?.id ?: IdGenerator.nextLongId()

    private lateinit var funcName: CodeFieldComp
    private lateinit var args: CodeFieldComp
    private lateinit var stdInSection: TSLStdInSection
    private lateinit var inputFiles: TSLInputFilesSection
    private lateinit var returnCheck: TSLReturnCheckSection
    private lateinit var dataChecks: TSLDataChecksSection

//    private lateinit var createObject: CodeEditorComp

    override val children: List<Component>
        get() = listOf(funcName, args, stdInSection, inputFiles, returnCheck, dataChecks)

    override fun create() = doInPromise {
//        createObject = CodeEditorComp(
//            CodeEditorComp.File(
//                "create_object.py", """
//            def create_object():
//                return ... # TODO: Tagasta vastav klass või isend
//        """.trimIndent()
//            ), showTabs = false, parent = this
//        )
//        val d = createObject.activeDoc
//        d?.markText(
//            objOf(
//                "line" to 0,
//                "ch" to 0,
//            ),
//            objOf(
//                "line" to 0,
//                "ch" to 100,
//            ),
//            objOf(
//                "readOnly" to true,
//                "atomic" to true,
//                "inclusiveLeft" to true,
//                "inclusiveRight" to true,
//                "selectLeft" to false,
//                "css" to "color: #999",
//            )
//        )

        funcName = CodeFieldComp(
            isRequired = true,
            initialValue = initialModel?.functionName.orEmpty(),
            onValidChange = { onValidChanged() },
            onValueChange = { onUpdate() },
            parent = this
        )

        args = CodeFieldComp(
            isRequired = false,
            fileExtension = "py",
            initialValue = initialModel?.arguments?.joinToString("\n").orEmpty(),
            helpText = Str.tslFuncArgsFieldHelp,
            onValueChange = { onUpdate() },
            parent = this
        )

        stdInSection = TSLStdInSection(initialModel?.standardInputData.orEmpty(), onUpdate, onValidChanged, this)

        inputFiles = TSLInputFilesSection(
            initialModel?.inputFiles.orEmpty().associate { it.fileName to it.fileContent }.toMutableMap(),
            onUpdate, onValidChanged, this
        )

        returnCheck = TSLReturnCheckSection(initialModel?.returnValueCheck, onUpdate, onValidChanged, this)
        dataChecks =
            TSLDataChecksSection(initialModel?.genericChecks.orEmpty().toMutableList(), onUpdate, onValidChanged, this)

    }

    override fun render() = template(
        """
            <ez-tsl-function-call-test>
                <div style='color: var(--ez-text-inactive);'>{{funcName}}</div>
                $funcName
                
                <ez-tsl-section-title>{{inputs}}</ez-tsl-section-title>
                <ez-flex style='align-items: baseline; color: var(--ez-text-inactive); margin-top: 1.5rem;'>
                    <ez-inline-flex style='align-self: center; margin-right: 1rem;'>${Icons.tslFunctionArgs}</ez-inline-flex>
                    {{args}}
                </ez-flex>
                <div style='padding-left: 3rem; margin-bottom: 3rem;'>
                    $args
                </div>
                
                $stdInSection
                $inputFiles
                
                <ez-tsl-section-title>{{checks}}</ez-tsl-section-title>
                $returnCheck
                $dataChecks
            </ez-tsl-function-call-test>
            
        """.trimIndent(),
        "funcName" to Str.tslFuncName,
        "inputs" to Str.tslInputs,
        "checks" to Str.tslChecks,
        "args" to Str.tslFuncArgs,
    )

    override fun getTSLModel(): Test {
        return FunctionExecutionTest(
            testId,
            funcName.getValue(),
            FunctionType.FUNCTION,
            arguments = getArgs(),
            returnValueCheck = returnCheck.getCheck(),
            standardInputData = stdInSection.getInputs(),
            inputFiles = inputFiles.updateAndGetFiles().map {
                FileData(it.key, it.value)
            },
            genericChecks = dataChecks.updateAndGetChecks(),
        )
    }

    override fun setEditable(nowEditable: Boolean) {
//        createObject.setFileEditable(createObject.getActiveTabFilename()!!, nowEditable)
        funcName.isDisabled = !nowEditable
        args.isDisabled = !nowEditable
        stdInSection.setEditable(nowEditable)
        returnCheck.setEditable(nowEditable)
        dataChecks.setEditable(nowEditable)
        inputFiles.setEditable(nowEditable)
    }

    override fun isValid() = funcName.getValue().isNotBlank() &&
            stdInSection.isValid() &&
            returnCheck.isValid() &&
            dataChecks.isValid() &&
            inputFiles.isValid()

    private fun getArgs() = args.getValue().split("\n").filter(String::isNotBlank)
}