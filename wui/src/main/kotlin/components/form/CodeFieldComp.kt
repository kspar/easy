package components.form

import components.code_editor.old.OldCodeEditorComp
import observeValueChange
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemByIdOrNull
import template


class CodeFieldComp(
    fileExtension: String = "",
    private val isRequired: Boolean,
    private val placeholder: String? = null,
    isDisabled: Boolean = false,
    private val initialValue: String = "",
    private val helpText: String = "",
    private val onValidChange: ((Boolean) -> Unit)? = null,
    private val onValueChange: (suspend (String) -> Unit)? = null,
    private val trimValue: Boolean = true,
    parent: Component
) : Component(parent) {

    private val editorFilename = IdGenerator.nextId() + "." + fileExtension

    var isDisabled: Boolean = isDisabled
        set(value) {
            field = value
            editor.setFileEditable(editorFilename, !value)
        }

    private var isCurrentlyValid = !isRequired || initialValue.isNotBlank()

    private lateinit var editor: OldCodeEditorComp

    override val children: List<Component>
        get() = listOf(editor)

    override fun create() = doInPromise {
        editor = OldCodeEditorComp(
            OldCodeEditorComp.File(
                editorFilename,
                initialValue,
                if (isDisabled) OldCodeEditorComp.Edit.READONLY else OldCodeEditorComp.Edit.EDITABLE
            ),
            placeholder = placeholder,
            showLineNumbers = false,
            showTabs = false,
            parent = this
        )
    }

    override fun render() = template(
        """
            $editor
            <ez-code-editor-field-help>{{helpText}}</ez-code-editor-field-help>
        """.trimIndent(),
        "helpText" to helpText,
    )

    override fun postRender() {
        doInPromise {
            observeValueChange(
                200, 200,
                valueProvider = { getValue() },
                continuationConditionProvider = { getElemByIdOrNull(editor.dstId) != null },
                action = { newValue ->
                    onValueChange?.invoke(newValue)
                    val isNowValid = !isRequired || newValue.isNotBlank()
                    if (isNowValid != isCurrentlyValid)
                        onValidChange?.invoke(isNowValid)
                },
            )
        }
    }

    fun getValue() = editor.getFileValue(editorFilename).let { if (trimValue) it.trim() else it }
}