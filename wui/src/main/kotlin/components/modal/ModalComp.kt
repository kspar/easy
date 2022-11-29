package components.modal

import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import libheaders.MModalInstance
import libheaders.Materialize
import org.w3c.dom.Element
import plainDstStr
import rip.kspar.ezspa.*
import tmRender
import kotlin.js.Promise


open class ModalComp<T>(
    private val title: String?,
    private val defaultReturnValue: T,
    private val fixFooter: Boolean = false,
    private val isWide: Boolean = false,
    bodyCompsProvider: ((ModalComp<T>) -> List<Component>)? = null,
    footerCompsProvider: ((ModalComp<T>) -> List<Component>)? = null,
    private val onOpen: (() -> Unit)? = null,
    parent: Component?,
    private val id: Modal,
    private val modalId: String = IdGenerator.nextId(),
) : Component(parent, id.name) {

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

    override fun create() = doInPromise {
        // Create dst in modals container
        val dstId = id.name
        if (getElemBySelector("#ez-modals #$dstId") == null)
            getElemById("ez-modals").appendHTML(plainDstStr(dstId))
    }

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

    fun setTitle(title: String?) {
        if (title != null) {
            modalElement.getElemBySelector(".modal-title").textContent = title
        } else {
            // Not sure: removing the element makes it impossible to set a title later
            modalElement.getElemBySelector(".modal-title").remove()
        }
    }

    fun setLoading(isLoading: Boolean) {
        modalElement.getElemBySelector(".progress").let {
            if (isLoading)
                it.removeClass("hidden")
            else
                it.addClass("hidden")
        }
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
            onOpen?.invoke()
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