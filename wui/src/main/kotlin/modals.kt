import libheaders.MModalInstance
import libheaders.Materialize
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import rip.kspar.ezspa.*
import kotlin.js.Promise

open class EzModalComp<T>(
    private val defaultReturnValue: T,
    parent: Component?,
    val modalId: String = IdGenerator.nextId(),
) : Component(parent) {

    private val modalElement: Element
        get() = getElemById(modalId)

    private var mModal: MModalInstance? = null

    private var returnValue: T = defaultReturnValue

    private val listenerProducers: MutableList<() -> ActiveListener> = mutableListOf()

    private var activeListeners: MutableSet<ActiveListener> = mutableSetOf()

    override fun render(): String = tmRender("t-c-modal", "id" to modalId)

    open fun setContent(contentHtml: String) {
        modalElement.innerHTML = contentHtml
    }

    fun addListener(listenerProducer: () -> ActiveListener) {
        listenerProducers.add(listenerProducer)
        activeListeners.add(listenerProducer())
    }

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
        refreshListeners()
    }

    private fun refreshListeners() {
        activeListeners.forEach { it.remove() }
        activeListeners.clear()

        activeListeners = listenerProducers.map { it() }.toMutableSet()
    }
}


class BinaryModalComp(
    private val primaryBtnText: String,
    private val secondaryBtnText: String,
    private val primaryBtnLoadingText: String? = null,
    private val secondaryBtnLoadingText: String? = null,
    parent: Component?,
) : EzModalComp<Boolean>(false, parent) {

    private var primaryAction: (suspend () -> Boolean)? = null

    private val trailerHtml = tmRender(
        "t-c-binary-modal-footer",
        "modalId" to modalId,
        "primaryText" to primaryBtnText,
        "secondaryText" to secondaryBtnText
    )

    private val primaryBtnListenerProducer = {
        getElemByIdAs<HTMLButtonElement>("modal-primary-btn-$modalId").onSingleClickWithDisabled(primaryBtnLoadingText) {
            val actionResult = primaryAction?.invoke() ?: true
            super.closeAndReturnWith(actionResult)
        }
    }

    private val secondaryBtnListenerProducer = {
        getElemByIdAs<HTMLButtonElement>("modal-secondary-btn-$modalId").onSingleClickWithDisabled(
            secondaryBtnLoadingText
        ) {
            super.closeAndReturnWith(false)
        }
    }

    override fun setContent(contentHtml: String) {
        super.setContent("<div class=\"modal-content\">$contentHtml</div>$trailerHtml")
        super.addListener(primaryBtnListenerProducer)
        super.addListener(secondaryBtnListenerProducer)
    }

    fun setPrimaryAction(action: suspend () -> Boolean) {
        primaryAction = action
    }
}
