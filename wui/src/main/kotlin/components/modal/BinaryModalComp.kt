package components.modal

import components.form.ButtonComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise


open class BinaryModalComp<T>(
    title: String?,
    primaryBtnText: String,
    secondaryBtnText: String,
    primaryBtnLoadingText: String? = null,
    secondaryBtnLoadingText: String? = null,
    defaultReturnValue: T,
    primaryBtnType: ButtonComp.Type = ButtonComp.Type.FILLED,
    val primaryButtonEnabledInitial: Boolean = true,
    fixFooter: Boolean = false,
    isWide: Boolean = false,
    open var primaryAction: (suspend () -> T)? = null,
    open var secondaryAction: (suspend () -> Unit)? = null,
    open var primaryPostAction: (suspend () -> Unit)? = null,
    open var secondaryPostAction: (suspend () -> Unit)? = primaryPostAction,
    onOpened: (() -> Unit)? = null,
    htmlClasses: String = "",
    parent: Component?,
) : ModalComp<T>(
    title, defaultReturnValue,
    fixFooter = fixFooter, isWide = isWide, onOpened = onOpened, htmlClasses = htmlClasses,
    parent = parent
) {

    val primaryButton = ButtonComp(
        primaryBtnType, primaryBtnText, null,
        enabled = primaryButtonEnabledInitial,
        clickedLabel = primaryBtnLoadingText,
        onClick = {
            val actionResult = primaryAction?.invoke() ?: defaultReturnValue
            super.closeAndReturnWith(actionResult)
        },
        onPostClick = { primaryPostAction?.invoke() },
        parent = this
    )

    val secondaryButton = ButtonComp(
        ButtonComp.Type.TEXT, secondaryBtnText, null,
        clickedLabel = secondaryBtnLoadingText,
        onClick = {
            secondaryAction?.invoke()
            super.closeAndReturnWith(defaultReturnValue)
        },
        onPostClick = { secondaryPostAction?.invoke() },
        parent = this
    )

    override fun create() = doInPromise {
        super.create().await()
        super.setFooterComps {
            listOf(secondaryButton, primaryButton)
        }
    }
}
