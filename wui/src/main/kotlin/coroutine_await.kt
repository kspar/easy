import kotlin.coroutines.*
import kotlin.js.Promise


// Adopted from https://discuss.kotlinlang.org/t/async-await-on-the-client-javascript/2412/3

suspend fun <T> Promise<T>.await(): T = suspendCoroutine { cont ->
    then({ cont.resume(it) }, { cont.resumeWithException(it) })
}

fun launch(block: suspend () -> Unit) {
    block.startCoroutine(object : Continuation<Unit> {
        override fun resumeWith(result: Result<Unit>) {}
        override val context: CoroutineContext get() = EmptyCoroutineContext
    })
}
