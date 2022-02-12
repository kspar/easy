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
    private val fixFooter: Boolean = false,
    private val isWide: Boolean = false,
    bodyCompsProvider: ((ModalComp<T>) -> List<Component>)? = null,
    footerCompsProvider: ((ModalComp<T>) -> List<Component>)? = null,
    parent: Component?,
    dstId: String = IdGenerator.nextId(),
    private val modalId: String = IdGenerator.nextId(),
) : Component(parent, dstId) {

    private val modalElement: Element
        get() = getElemById(modalId)

    private var mModal: MModalInstance? = null

    // TODO: listener refresh logic is probably not needed since children are comps that manage their own listeners,
    //  remove at some point if it becomes clear that it's not needed
//    private val listenerProducers: MutableList<() -> ActiveListener> = mutableListOf()
//    private var activeListeners: MutableSet<ActiveListener> = mutableSetOf()

    private var bodyComps = bodyCompsProvider?.invoke(this) ?: emptyList()
    private var footerComps = footerCompsProvider?.invoke(this) ?: emptyList()

    // Modal return value is communicated through this, set when the modal is closing
    private var returnValue: T = defaultReturnValue

    override val children: List<Component>
        get() = bodyComps + footerComps

    override fun render(): String = tmRender(
        "t-c-modal",
        "id" to modalId,
        "fixedFooter" to fixFooter,
        "wide" to isWide,
        "title" to title,
        "bodyComps" to bodyComps.map { mapOf("id" to it.dstId) },
        "footerComps" to footerComps.map { mapOf("id" to it.dstId) },
    )


    fun setContentComps(componentsProvider: (ModalComp<T>) -> List<Component>) {
        this.bodyComps = componentsProvider(this)
    }

    fun setFooterComps(componentsProvider: (ModalComp<T>) -> List<Component>) {
        this.footerComps = componentsProvider(this)
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

    /**
     * Called by other components when the modal is visible to dismiss it and return a value
     */
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