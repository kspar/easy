package pages.exercise

import debug
import kotlinx.coroutines.await
import pages.exercise.editor.AutoassessAttrsComp
import pages.exercise.editor.AutoassessCodeEditorComp
import pages.exercise.editor.AutoassessEditorComp
import pages.exercise.editor.AutoassessTSLEditorComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import tmRender
import warn
import kotlin.js.Promise


class AutoAssessmentTabComp(
    private val savedProps: AutoAssessProps?,
    private val onValidChanged: (Boolean) -> Unit,
    parent: Component?
) : Component(parent) {

    data class AutoAssessProps(
        val evalScript: String,
        val assets: Map<String, String>,
        val containerImage: String,
        // null when invalid value entered
        val maxTime: Int?,
        val maxMem: Int?,
    )

    data class SavableProps(
        val evalScript: String,
        val assets: Map<String, String>,
        val containerImage: String,
        val maxTime: Int,
        val maxMem: Int,
    )

    private var aaProps: AutoAssessProps? = savedProps
    private var isEditable = false

    private lateinit var attrs: AutoassessAttrsComp

    // Code editor or UI editor - show if has aa
    private var editor: AutoassessEditorComp? = null

    override val children: List<Component>
        get() = listOfNotNull(attrs, editor)

    override fun create(): Promise<*> = doInPromise {

        val props = aaProps
        if (props != null) {
            // Has autoassessment already
            val type = AutoEvalTypes.templates.singleOrNull { it.container == props.containerImage }
            val typeName = type?.name ?: props.containerImage

            attrs = AutoassessAttrsComp(
                typeName, props.containerImage, props.maxTime, props.maxMem, isEditable,
                ::changeType, onValidChanged, this
            )

            when (val editorType = type?.editor) {
                AutoEvalTypes.TypeEditor.TSL_COMPOSE -> {
                    editor = AutoassessTSLEditorComp(
                        props.evalScript, props.assets,
                        isEditable, this
                    )
                }
                AutoEvalTypes.TypeEditor.CODE_EDITOR -> {
                    editor = AutoassessCodeEditorComp(
                        props.evalScript, props.assets,
                        isEditable, this
                    )
                }
                null -> {
                    warn { "No type mapped for container image $editorType" }
                }
            }
        } else {
            // Has no autoassess

            attrs = AutoassessAttrsComp(
                "–", null, null, null, isEditable, ::changeType, onValidChanged, this
            )
            editor = null
        }
    }

    override fun render(): String = tmRender(
        "t-c-exercise-tab-aa",
        "attrsDstId" to attrs.dstId,
        "editorDstId" to editor?.dstId,
    )

    override fun postChildrenBuilt() {
        attrs.validateInitial()
    }

    override fun hasUnsavedChanges(): Boolean {
        // children have changes or props have changed
        return super.hasUnsavedChanges() || aaProps != savedProps
    }

    suspend fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        if (!nowEditable) {
            // restore saved
            aaProps = savedProps
            createAndBuild().await()
        }
        attrs.setEditable(nowEditable)
        editor?.setEditable(nowEditable)
    }

    fun getEditedProps(): SavableProps? {
        val container = attrs.getEditedContainerImage()
        return if (container != null) {
            SavableProps(
                editor!!.getEvalScript(),
                editor!!.getAssets(),
                container,
                attrs.getEditedTime()!!,
                attrs.getEditedMem()!!,
            )
        } else null
    }

    fun isValid() = attrs.isValid() && (editor?.isValid() ?: true)

    // TODO
//    fun getEditorActiveTabId() = (editor as? CodeEditorComp)?.getActiveTabFilename()
//    fun setEditorActiveTabId(editorTabId: String?) {
//        val e = editor
//        if (editorTabId != null && e is CodeEditorComp)
//            e.setActiveTabByFilename(editorTabId)
//    }

    private suspend fun changeType(typeId: String?) {
        debug { "Set type to $typeId" }

        val props = aaProps
        aaProps = when {
            typeId == null -> {
                // Deselected aa
                null
            }
            props == null -> {
                // No aa previously selected, get from template
                val template = AutoEvalTypes.templates.single { it.container == typeId }
                AutoAssessProps(
                    template.evaluateScript, template.assets, template.container,
                    template.allowedTime, template.allowedMemory
                )
            }
            else -> {
                // Type changed, only change container image respecting any changes made

                // Assume editor exists
                AutoAssessProps(
                    editor!!.getEvalScript(),
                    editor!!.getAssets(),
                    typeId,
                    attrs.getEditedTime(),
                    attrs.getEditedMem(),
                )
            }
        }

        createAndBuild().await()
    }
}
