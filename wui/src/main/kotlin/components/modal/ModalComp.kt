package components.modal

import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import libheaders.MModalInstance
import libheaders.Materialize
import org.w3c.dom.Element
import rip.kspar.ezspa.*
import template
import kotlin.js.Promise


open class ModalComp<T>(
    private val title: String?,
    private val defaultReturnValue: T,
    private val fixFooter: Boolean = false,
    private val isWide: Boolean = false,
    bodyCompsProvider: ((ModalComp<T>) -> List<Component>)? = null,
    footerCompsProvider: ((ModalComp<T>) -> List<Component>)? = null,
    private val onOpen: (() -> Unit)? = null,
    private val htmlClasses: String = "",
    parent: Component?,
) : Component(parent) {

    private val modalId: String = IdGenerator.nextId()
    private val modalElement: Element
        get() = getElemById(modalId)

    private var mModal: MModalInstance? = null

    private var bodyComps = bodyCompsProvider?.invoke(this) ?: emptyList()
    private var footerComps = footerCompsProvider?.invoke(this) ?: emptyList()

    // Modal return value is communicated through this, set when the modal is closing
    private var returnValue: T = defaultReturnValue

    override val children: List<Component>
        get() = bodyComps + footerComps

    override fun create() = doInPromise {
        // Create destination in global modal container.
        // This hack is needed because components can be rendered inside elements that have a separate containing block.
        // This would cause children-modals to be rendered somewhere where they cannot be positioned as absolute/fixed
        // relative to the viewport.
        // https://stackoverflow.com/questions/76005559/how-to-position-an-absolute-fixed-element-relative-to-the-viewport-instead-of-pa
        getElemById("ez-modals").appendHTML(plainDstStr(dstId))
    }

    override fun render(): String = template(
        """
            <div id="{{id}}" class="modal {{#fixedFooter}}fixed-footer{{/fixedFooter}} {{#wide}}wide{{/wide}} {{htmlClasses}}">
                <div class="progress hidden">
                    <div class="indeterminate"></div>
                </div>
                <div class="modal-content">
                    {{#title}}<h4 class="modal-title">{{title}}</h4>{{/title}}
                    {{#bodyComps}}
                        <ez-dst id="{{id}}"></ez-dst>
                    {{/bodyComps}}
                </div>
                <div class="modal-footer">
                    {{#footerComps}}
                        <ez-dst id="{{id}}"></ez-dst>
                    {{/footerComps}}
                </div>
            </div>
        """.trimIndent(),
        "id" to modalId,
        "fixedFooter" to fixFooter,
        "wide" to isWide,
        "htmlClasses" to htmlClasses,
        "title" to title,
        "bodyComps" to bodyComps.map { mapOf("id" to it.dstId) },
        "footerComps" to footerComps.map { mapOf("id" to it.dstId) },
    )

    override fun destroyThis() {
        // Remove the destination element since it was created in [create].
        getElemByIdOrNull(dstId)?.remove()
    }


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
    }
}