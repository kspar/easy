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
    parent: Component?,
    dstId: String = IdGenerator.nextId(),
) : ModalComp<T>(title, defaultReturnValue, parent, dstId) {

    private var primaryAction: (suspend () -> T)? = null
    private var primaryPostAction: (suspend () -> Unit)? = null
    private var secondaryAction: (suspend () -> Unit)? = null
    private var secondaryPostAction: (suspend () -> Unit)? = null

    protected val primaryButtonComp = ButtonComp(ButtonComp.Type.PRIMARY, primaryBtnText, {
        val actionResult = primaryAction?.invoke() ?: defaultReturnValue
        super.closeAndReturnWith(actionResult)
    }, primaryBtnLoadingText, { primaryPostAction?.invoke() }, this)

    protected val secondaryBtnComp = ButtonComp(ButtonComp.Type.SECONDARY, secondaryBtnText, {
        secondaryAction?.invoke()
        super.closeAndReturnWith(defaultReturnValue)
    }, secondaryBtnLoadingText, { secondaryPostAction?.invoke() }, this)

    override fun create() = doInPromise {
        super.create().await()
        super.setFooter(secondaryBtnComp, primaryButtonComp)
    }

    fun setPrimaryAction(action: (suspend () -> T)? = null, postAction: (suspend () -> Unit)? = null) {
        primaryAction = action
        primaryPostAction = postAction
    }

    fun setSecondaryAction(action: (suspend () -> Unit)? = null, postAction: (suspend () -> Unit)? = null) {
        secondaryAction = action
        secondaryPostAction = postAction
    }
}
