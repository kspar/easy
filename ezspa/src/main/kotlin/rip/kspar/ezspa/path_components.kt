package rip.kspar.ezspa

internal sealed interface PathComponent

internal data class PathParam(val key: String) : PathComponent
internal data class PathString(val str: String) : PathComponent

/**
 * Map subclass where the index operator [] returns a non-nullable value or throws an exception
 */
class CertainMap<K, V>(original: Map<K, V>) : LinkedHashMap<K, V>(original) {
    override operator fun get(key: K): V =
        super.get(key) ?: throw NoSuchElementException("Key $key is missing in the map.")
}

fun <K, V> Map<K, V>.toCertainMap(): CertainMap<K, V> = CertainMap(this)
