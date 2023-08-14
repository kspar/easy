package pages.exercise

import debug
import kotlinx.coroutines.await
import pages.exercise.editor.*
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
                        isEditable, { onValidChanged(true) /* random bool not used */ }, this
                    )
                }

                AutoEvalTypes.TypeEditor.TSL_YAML -> {
                    editor = AutoassessTSLYAMLEditorComp(
                        props.evalScript, props.assets,
                        isEditable, onValidChanged, this
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

    fun getEditorActiveView() = editor?.getActiveView()
    fun setEditorActiveView(view: AutoassessEditorComp.ActiveView?) = editor?.setActiveView(view)

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

    fun refreshTSLTabs() = (editor as? AutoassessTSLEditorComp)?.tslRoot?.tabs?.refreshIndicator()

    private suspend fun changeType(typeId: String?) {
        debug { "Set type to $typeId" }

        val oldProps = aaProps
        val oldType = AutoEvalTypes.templates.singleOrNull { it.container == oldProps?.containerImage }
        val newType = AutoEvalTypes.templates.singleOrNull { it.container == typeId }

        aaProps = when {
            // Deselected autoeval
            newType == null -> {
                // Scripts and attrs are cleared
                null
            }
            // No autoeval previously selected or editor types are different
            oldType == null || oldType.editor != newType.editor -> {
                // Get from template
                AutoAssessProps(
                    newType.evaluateScript, newType.assets, newType.container,
                    newType.allowedTime, newType.allowedMemory
                )
            }
            // Container changed, editor type same
            else -> {
                // Check if scripts have changed from previous template - then keep current, else from new template
                // Can assume editor exists
                val currentEval = editor!!.getEvalScript()
                val currentAssets = editor!!.getAssets()

                val (eval, assets) =
                    if (currentEval != oldType.evaluateScript || currentAssets != oldType.assets)
                        currentEval to currentAssets
                    else
                        newType.evaluateScript to newType.assets

                // Check if attrs have changed from previous template - then keep current, else from new template
                val currentTime = attrs.getEditedTime()
                val currentMem = attrs.getEditedMem()
                val (time, mem) = if (currentTime != oldType.allowedTime || currentMem != oldType.allowedMemory)
                    currentTime to currentMem
                else
                    newType.allowedTime to newType.allowedMemory


                AutoAssessProps(
                    eval,
                    assets,
                    newType.container,
                    time,
                    mem,
                )
            }
        }

        createAndBuild().await()
    }
}
