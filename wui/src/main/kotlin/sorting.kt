private val chunkRegex = Regex("\\D+|\\d+(\\.\\d+)?")
private val numericChunkRegex = Regex("\\d+(\\.\\d+)?")

private fun chunkItUp(str: String) = chunkRegex.findAll(str).map { it.value }.toList()

val HumanStringComparator = Comparator<String?> { a, b ->

    // Nulls first
    if (a == null && b == null)
        return@Comparator 0
    if (a == null)
        return@Comparator -1
    if (b == null)
        return@Comparator 1

    // Extract chunks of non-digits or digits with optional decimals
    val chunksA = chunkItUp(a)
    val chunksB = chunkItUp(b)

    // Compare corresponding chunks from both strings
    chunksA.zip(chunksB).forEach { (chunk1, chunk2) ->
        val comparison = when {
            // If both chunks are numeric, compare them as doubles
            chunk1.matches(numericChunkRegex) && chunk2.matches(numericChunkRegex) -> {
                chunk1.toDouble().compareTo(chunk2.toDouble())
            }
            // Otherwise, compare them lexically as strings
            else -> chunk1.compareTo(chunk2)
        }

        // If the comparison yields a non-zero result, return it, otherwise look at the next chunks
        if (comparison != 0)
            return@Comparator comparison
    }

    // If all corresponding chunks are identical, compare based on chunk count,
    // so substrings get placed before their superstrings
    chunksA.size.compareTo(chunksB.size)
}
