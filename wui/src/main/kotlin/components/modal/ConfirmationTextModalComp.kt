package components.modal

import components.TextComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise


class ConfirmationTextModalComp(
    title: String?,
    primaryBtnText: String,
    secondaryBtnText: String,
    primaryBtnLoadingText: String? = null,
    secondaryBtnLoadingText: String? = null,
    parent: Component?,
) : BinaryModalComp<Boolean>(
    title,
    primaryBtnText,
    secondaryBtnText,
    primaryBtnLoadingText,
    secondaryBtnLoadingText,
    false,
    parent
) {

    private var textComp = TextComp("", this)

    override fun create() = doInPromise {
        super.create().await()
        super.setContent(textComp)
    }

    fun setText(text: String) {
        textComp.text = text
        textComp.rebuild()
    }
}
