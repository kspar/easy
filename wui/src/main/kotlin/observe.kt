import kotlinx.coroutines.await
import kotlin.browser.window
import kotlin.js.Promise


fun sleep(timeMs: Int) =
        Promise<Unit> { resolve, _ -> window.setTimeout(resolve, timeMs) }


suspend fun <T> observeValueChange(idleThresholdMs: Int, pollTimeMs: Int, doActionFirst: Boolean = false,
                                   valueProvider: suspend () -> T,
                                   action: suspend (T) -> Unit,
                                   continuationConditionProvider: suspend () -> Boolean,
                                   idleCallback: (suspend () -> Unit)? = null) {

    var lastValue: T = valueProvider.invoke()
    var idleForMs = 0
    var actionDone = true

    // Start with action
    if (doActionFirst) {
        val currentValue = valueProvider.invoke()
        action.invoke(currentValue)
    }

    while (true) {
        sleep(pollTimeMs).await()

        if (!continuationConditionProvider.invoke()) {
            return
        }

        val currentValue = valueProvider.invoke()

        if (currentValue == lastValue) {
            if (!actionDone) {
                idleForMs += pollTimeMs
            }
        } else {
            // Value has changed, start counting idle time
            // Call idle callback if state just changed from <action done> to <waiting for idle to do action>
            if (actionDone) {
                idleCallback?.invoke()
            }
            actionDone = false
            lastValue = currentValue
            idleForMs = 0
        }

        if (!actionDone && idleForMs >= idleThresholdMs) {
            action.invoke(currentValue)
            actionDone = true
        }
    }
}
