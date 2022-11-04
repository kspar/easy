package pages.exercise.editor

import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class AutoassessTSLEditorComp(
    private val evaluateScript: String,
    private val assets: Map<String, String>,
    startEditable: Boolean,
    parent: Component?,
) : AutoassessEditorComp(parent) {


    private var isEditable = startEditable


    override val children: List<Component>
        get() = listOf()

    override fun create() = doInPromise {

    }

    override fun render() = "TSL"

    override suspend fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        createAndBuild().await()
    }

    override fun getEvalScript(): String {
        return ""
    }

    override fun getAssets(): Map<String, String> {
        return emptyMap()
    }

}