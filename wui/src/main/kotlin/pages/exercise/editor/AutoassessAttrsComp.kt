package pages.exercise.editor

import components.AttrsComp
import kotlinx.coroutines.await
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import kotlin.js.Promise


class AutoassessAttrsComp(
    private val visualTypeName: String,
    private val containerImage: String?,
    private val maxTime: Int?,
    private val maxMem: Int?,
    startEditable: Boolean,
    private val onTypeChanged: suspend (String?) -> Unit,
    private val onValidChanged: (Boolean) -> Unit,
    parent: Component?
) : Component(parent) {

    private var isEditable = startEditable

    private lateinit var attrs: Component

    override val children: List<Component>
        get() = listOf(attrs)

    override fun create(): Promise<*> = doInPromise {
        attrs = if (isEditable)
            AutoassessAttrsEditComp(containerImage, maxTime, maxMem, onTypeChanged, onValidChanged, this)
        else
            AttrsComp(
                buildMap {
                    set("Tüüp", visualTypeName)
                    if (maxTime != null)
                        set("Lubatud käivitusaeg", "$maxTime s")
                    if (maxMem != null)
                        set("Lubatud mälukasutus", "$maxMem MB")
                }, this
            )
    }

    override fun render() = plainDstStr(attrs.dstId)


    suspend fun setEditable(nowEditable: Boolean) {
        isEditable = nowEditable
        createAndBuild().await()
    }

    fun isValid() = attrs.let { if (it is AutoassessAttrsEditComp) it.isValid() else true }

    fun getEditedContainerImage() =
        (attrs as? AutoassessAttrsEditComp)?.getContainerImage()

    fun getEditedTime() =
        (attrs as? AutoassessAttrsEditComp)?.getTime()

    fun getEditedMem() =
        (attrs as? AutoassessAttrsEditComp)?.getMem()

    fun validateInitial() {
        (attrs as? AutoassessAttrsEditComp)?.validateInitial()
    }
}