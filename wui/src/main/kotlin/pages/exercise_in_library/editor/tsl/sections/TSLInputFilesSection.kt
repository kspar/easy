package pages.exercise_in_library.editor.tsl.sections

import Icons
import components.form.OldButtonComp
import components.form.OldIconButtonComp
import components.form.StringFieldComp
import components.form.TextFieldComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import show
import template
import translation.Str

class TSLInputFilesSection(
    private var files: MutableMap<String, String>,
    private val onUpdate: suspend () -> Unit,
    private val onValidChanged: () -> Unit,
    parent: Component,
) : Component(parent) {

    data class FileSection(
        val nameField: StringFieldComp, val deleteBtn: OldIconButtonComp, val valueField: TextFieldComp
    )

    private val addBtn =
        OldButtonComp(OldButtonComp.Type.FLAT, Str.tslInputFile, Icons.add, ::addFile, parent = this)

    private lateinit var sections: List<FileSection>


    override val children: List<Component>
        get() = listOf(addBtn) + sections.flatMap {
            listOf(it.nameField, it.deleteBtn, it.valueField)
        }

    override fun create() = doInPromise {
        sections = files.map {
            val filenameField = StringFieldComp(
                "", true,
                placeholder = "file.txt",
                fieldNameForMessage = Str.tslInputFileName,
                initialValue = it.key,
                onValidChange = { onValidChanged() },
                onValueChange = { onUpdate() },
                parent = this
            )
            FileSection(
                filenameField,
                OldIconButtonComp(
                    Icons.deleteUnf, Str.doDelete, onClick = {
                        updateAndGetFiles()
                        files.remove(filenameField.getValue())
                        createAndBuild().await()
                        onUpdate()
                    },
                    parent = this
                ),
                TextFieldComp(
                    "", false,
                    helpText = Str.tslInputFileContent,
                    startActive = true,
                    initialValue = it.value,
                    onValueChange = { onUpdate() },
                    onValidChange = { onValidChanged() },
                    parent = this
                ),
            )
        }
    }

    override fun render() = template(
        """
            {{#files}}
                <ez-flex style='align-items: baseline; color: var(--ez-text-inactive);'>
                    <ez-inline-flex style='align-self: center; margin-right: 1rem;'>${Icons.tslInputFile}</ez-inline-flex>
                    {{sent1}} <ez-tsl-inline-field id='{{filenameDst}}'></ez-tsl-inline-field>
                    <ez-inline-flex style='align-self: center;' id='{{deleteBtnDst}}'></ez-inline-flex>
                </ez-flex>
                <ez-tsl-input-data-field id='{{contentDst}}'></ez-tsl-input-data-field>
            {{/files}}
            <ez-tsl-add-button id='{{btnDst}}'></ez-tsl-add-button>
        """.trimIndent(),
        "files" to sections.map {
            mapOf(
                "filenameDst" to it.nameField.dstId,
                "deleteBtnDst" to it.deleteBtn.dstId,
                "contentDst" to it.valueField.dstId,
            )
        },
        "btnDst" to addBtn.dstId,
        "sent1" to Str.tslInputFileSent1,
    )

    fun updateAndGetFiles(): Map<String, String> {
        files = sections.associate {
            it.nameField.getValue() to it.valueField.getValue()
        }.toMutableMap()
        return files
    }

    private suspend fun addFile() {
        updateAndGetFiles()
        files["file${IdGenerator.nextLongId()}.txt"] = ""
        createAndBuild().await()
        onUpdate()
    }

    fun setEditable(nowEditable: Boolean) {
        sections.forEach {
            it.nameField.isDisabled = !nowEditable
            it.valueField.isDisabled = !nowEditable
        }
        rebuild()
        sections.forEach {
            it.deleteBtn.show(nowEditable)
        }
        addBtn.show(nowEditable)
    }

    fun isValid() = sections.all {
        it.nameField.isValid && it.valueField.isValid
    }
}
