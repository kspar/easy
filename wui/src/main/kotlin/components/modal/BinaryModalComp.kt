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
    isWide: Boolean = false,
    open var primaryAction: (suspend () -> T)? = null,
    open var secondaryAction: (suspend () -> Unit)? = null,
    open var primaryPostAction: (suspend () -> Unit)? = null,
    open var secondaryPostAction: (suspend () -> Unit)? = primaryPostAction,
    parent: Component?,
    dstId: String = IdGenerator.nextId(),
) : ModalComp<T>(title, defaultReturnValue, isWide = isWide, parent = parent, dstId = dstId) {

    val primaryButtonComp = ButtonComp(primaryBtnType, primaryBtnText, {
        val actionResult = primaryAction?.invoke() ?: defaultReturnValue
        super.closeAndReturnWith(actionResult)
    }, primaryBtnLoadingText, { primaryPostAction?.invoke() }, this)

    val secondaryBtnComp = ButtonComp(ButtonComp.Type.FLAT, secondaryBtnText, {
        secondaryAction?.invoke()
        super.closeAndReturnWith(defaultReturnValue)
    }, secondaryBtnLoadingText, { secondaryPostAction?.invoke() }, this)


    override fun create() = doInPromise {
        super.create()?.await()
        super.setFooterComps {
            listOf(secondaryBtnComp, primaryButtonComp)
        }
    }
}
