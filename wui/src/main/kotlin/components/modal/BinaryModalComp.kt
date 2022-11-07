package components.modal

import components.form.ButtonComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise


open class BinaryModalComp<T>(
    title: String?,
    primaryBtnText: String,
    secondaryBtnText: String,
    primaryBtnLoadingText: String? = null,
    secondaryBtnLoadingText: String? = null,
    defaultReturnValue: T,
    primaryBtnType: ButtonComp.Type = ButtonComp.Type.PRIMARY,
    val primaryButtonEnabledInitial: Boolean = true,
    fixFooter: Boolean = false,
    isWide: Boolean = false,
    open var primaryAction: (suspend () -> T)? = null,
    open var secondaryAction: (suspend () -> Unit)? = null,
    open var primaryPostAction: (suspend () -> Unit)? = null,
    open var secondaryPostAction: (suspend () -> Unit)? = primaryPostAction,
    onOpen: (() -> Unit)? = null,
    parent: Component?,
    dstId: String = IdGenerator.nextId(),
) : ModalComp<T>(
    title, defaultReturnValue,
    fixFooter = fixFooter, isWide = isWide, onOpen = onOpen,
    parent = parent, dstId = dstId
) {

    val primaryButton = ButtonComp(primaryBtnType, primaryBtnText, null, {
        val actionResult = primaryAction?.invoke() ?: defaultReturnValue
        super.closeAndReturnWith(actionResult)
    }, primaryButtonEnabledInitial, primaryBtnLoadingText, { primaryPostAction?.invoke() }, this)

    val secondaryButton = ButtonComp(ButtonComp.Type.FLAT, secondaryBtnText, null, {
        secondaryAction?.invoke()
        super.closeAndReturnWith(defaultReturnValue)
    }, true, secondaryBtnLoadingText, { secondaryPostAction?.invoke() }, this)

    override fun create() = doInPromise {
        super.create()?.await()
        super.setFooterComps {
            listOf(secondaryButton, primaryButton)
        }
    }
}
