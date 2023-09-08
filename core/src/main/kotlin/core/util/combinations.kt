package core.util


// Copied from https://github.com/MarcinMoskala/KotlinDiscreteMathToolkit/issues/6

fun <T : Collection<U>, U> T.combinations(combinationSize: Int): Set<Set<U>> {
    val result = mutableSetOf<Set<U>>()
    forEachCombination(combinationSize) { result.add(it) }
    return result.toSet()
}

fun <T : Collection<U>, U> T.forEachCombination(combinationSize: Int, callback: (Set<U>) -> Unit) {
    when {
        combinationSize < 2 -> IllegalArgumentException("combinationSize must be at least 2, actual value: $combinationSize")
        this.size <= combinationSize -> callback(this.toSet())
    }

    doForEachCombination(this.toList(), combinationSize) { callback(it) }
}

private fun <U> doForEachCombination(
    source: List<U>,
    combinationSize: Int,
    depth: Int = 0,
    idx: Int = 0,
    tmp: MutableList<U> = source.subList(0, combinationSize).toMutableList(),
    callback: (Set<U>) -> Unit
) {
    for (i in idx..source.size - (combinationSize - depth)) {
        tmp[depth] = source[i]
        when (depth) {
            combinationSize - 1 -> callback(tmp.toSet()) // found new combination
            else -> doForEachCombination(source, combinationSize, depth + 1, i + 1, tmp, callback)
        }
    }
}