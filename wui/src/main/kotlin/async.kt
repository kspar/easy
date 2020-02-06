import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.js.Promise


fun <T> doInPromise(action: suspend () -> T): Promise<T> =
        Promise { resolve, reject ->
            MainScope().launch {
                try {
                    resolve(action())
                } catch (e: Throwable) {
                    reject(e)
                }
            }
        }


fun <T> Collection<Promise<T>>.unionPromise(): Promise<List<T>> =
        Promise.all(this.toTypedArray()).then { it.asList() }
