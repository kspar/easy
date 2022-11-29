package cache

import debug
import kotlin.js.Promise

class MemoryPromiseCache<K, V>(
    cacheName: String,
    producer: (K) -> Promise<V>,
) : MemoryCache<K, Promise<V>>(cacheName, producer) {

    @JsName("memoryPromiseCachePut")
    fun put(key: K, value: V) {
        super.put(key, Promise.resolve(value))
    }
}

open class MemoryCache<K, V>(
    private val cacheName: String,
    private val producer: (K) -> V,
) {
    private val cache: MutableMap<K, V> = mutableMapOf()

    fun get(key: K): V {
        debug { "Cache $cacheName: get key $key" }

        return cache.getOrPut(key) {
            debug { "Cache $cacheName: MISS for key $key (cache size: ${cache.size})" }
            producer(key)
        }
    }

    fun put(key: K, value: V) {
        cache[key] = value
    }

    fun invalidate(key: K) {
        cache.remove(key)
    }
}