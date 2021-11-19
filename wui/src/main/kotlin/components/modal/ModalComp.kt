package components.modal

import libheaders.MModalInstance
import libheaders.Materialize
import objOf
import org.w3c.dom.Element
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.getElemById
import tmRender
import kotlin.js.Promise


open class ModalComp<T>(
    private val title: String?,
    private val defaultReturnValue: T,
    parent: Component?,
    dstId: String = IdGenerator.nextId(),
    private val modalId: String = IdGenerator.nextId(),
) : Component(parent, dstId) {

    private val modalElement: Element
        get() = getElemById(modalId)

    private var mModal: MModalInstance? = null

    private var returnValue: T = defaultReturnValue

    // TODO: listener refresh logic is probably not needed since children are comps that manage their own listeners,
    //  remove at some point if it becomes clear that it's not needed
//    private val listenerProducers: MutableList<() -> ActiveListener> = mutableListOf()
//    private var activeListeners: MutableSet<ActiveListener> = mutableSetOf()

    private lateinit var bodyComps: List<Component>
    private var footerComps: List<Component> = emptyList()

    override val children: List<Component>
        get() = bodyComps + footerComps

    override fun render(): String = tmRender(
        "t-c-modal",
        "id" to modalId,
        "title" to title,
        "bodyComps" to bodyComps.map { mapOf("id" to it.dstId) },
        "footerComps" to footerComps.map { mapOf("id" to it.dstId) },
    )

    fun setContent(vararg components: Component) {
        setContent(components.toList())
    }

    fun setContent(components: List<Component>) {
        this.bodyComps = components
    }

    fun setFooter(vararg components: Component) {
        setFooter(components.toList())
    }

    fun setFooter(components: List<Component>) {
        this.footerComps = components
    }

//    fun addListener(listenerProducer: () -> ActiveListener) {
//        listenerProducers.add(listenerProducer)
//        activeListeners.add(listenerProducer())
//    }

    fun openWithClosePromise(): Promise<T> {
        val p = Promise<T> { resolve, _ ->
            val modal = Materialize.Modal.init(
                modalElement,
                objOf("onCloseStart" to { onModalClose(resolve) })
            )
            modal.open()
            mModal = modal
        }
        return p
    }

    fun closeAndReturnWith(returnValue: T) {
        this.returnValue = returnValue
        mModal?.close()
    }

    private fun onModalClose(resolve: (T) -> Unit) {
        resolve(returnValue)
        returnValue = defaultReturnValue
//        refreshListeners()
    }

//    private fun refreshListeners() {
//        activeListeners.forEach { it.remove() }
//        activeListeners.clear()
//
//        activeListeners = listenerProducers.map { it() }.toMutableSet()
//    }
}