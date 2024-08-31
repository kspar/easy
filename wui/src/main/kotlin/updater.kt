import components.ToastThing
import kotlinx.browser.window
import kotlinx.coroutines.await
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemBySelector
import rip.kspar.ezspa.sleep
import translation.Str


private val currentVersion = getElemBySelector("head > meta[name=\"ez-wui-version\"]").getAttribute("content")

fun startPollingForStaticUpdate() = doInPromise {
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
                    icon = ToastThing.ERROR_INFO,
                    isDismissable = false,
                    displayTime = ToastThing.PERMANENT
                )
                break
            }

        } catch (e: Throwable) {
            warn { "Static update poll failed: ${e.message}" }
        }

        sleep(AppProperties.STATIC_UPDATE_POLL_SEC * 1000).await()
    }
}
