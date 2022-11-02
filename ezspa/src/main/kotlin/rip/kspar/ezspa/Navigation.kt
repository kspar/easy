package rip.kspar.ezspa

import kotlinx.browser.window


object Navigation {

    private var unsavedChangesChecker: (() -> Boolean)? = null

    private var beforeUnloadAbort: AbortController? = null

    val hasUnsavedChanges: Boolean
        get() = unsavedChangesChecker?.invoke() ?: false


    fun catchNavigation(shouldPrevent: () -> Boolean) {
        if (unsavedChangesChecker != null) {
            EzSpa.Logger.warn { "Duplicate catchNavigation registered" }
        }

        val abort = AbortController()
        beforeUnloadAbort = abort

        window.addEventListener("beforeunload", {
            if (shouldPrevent()) {
                it.preventDefault()
                it.returnValue = ""
            }
        }, objOf("signal" to abort.signal))

        // internal links


        // history navigation
        unsavedChangesChecker = shouldPrevent
    }

    fun stopNavigationCatching() {
        unsavedChangesChecker = null
        beforeUnloadAbort!!.abort()
    }
}

