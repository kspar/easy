
import components.ToastThing
import kotlinx.browser.window
import kotlinx.coroutines.await
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemBySelector
import rip.kspar.ezspa.sleep
import translation.Str


private val currentVersion = getElemBySelector("head > meta[name=\"ez-wui-version\"]").getAttribute("content")

private val toastId = IdGenerator.nextId()

fun startPollingForStaticUpdate() = doInPromise {
    if (AppProperties.STATIC_UPDATE_ENABLED)
        while (true) {
            try {
                val latestVersion = window.fetch(
                    AppProperties.WUI_ROOT + "/static/version.txt",
                ).await().text().await()

                if (latestVersion != currentVersion) {
                    debug { "Static update found, current: $currentVersion, latest: $latestVersion" }

                    ToastThing(
                        Str.lahendusUpdated,
                        ToastThing.Action(Str.doRefresh, { window.location.reload() }),
                        icon = Icons.robot,
                        isDismissable = false,
                        displayTime = ToastThing.PERMANENT,
                        id = toastId
                    )
                }

            } catch (e: Throwable) {
                warn { "Static update poll failed: ${e.message}" }
            }

            sleep(AppProperties.STATIC_UPDATE_POLL_SEC * 1000).await()
        }
}
