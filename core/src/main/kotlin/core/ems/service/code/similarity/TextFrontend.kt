package core.ems.service.code.similarity

import de.jplag.SharedTokenType
import de.jplag.Token
import de.jplag.TokenType
import java.io.File
import java.text.Normalizer

/**
 * Anything no other frontend understands - an essay, or code in a language there is no frontend for -
 * compared word by word.
 *
 * One distinct token type per distinct word is the whole trick. JPlag compares token *types*, so a
 * single WORD type would make every text submission identical to every other; making the word itself
 * the type means Greedy String Tiling matches runs of the same words - ordinary text similarity, and
 * the right behaviour for an essay exercise. Words are letters, digits and underscores, lowercased;
 * punctuation and operators are not words, so for code this sees names and keywords and nothing else.
 */
internal object TextFrontend : SimilarityFrontend {

    /** Python's, which this inherited before it was calibrated on its own; words carry more than code tokens. */
    override val minimumTokenMatch = 6

    /**
     * A word, with the combining marks and invisible format characters inside it. They are part of the
     * word as displayed, so a word ended at them: an essay in decomposed form (`a` plus a combining
     * diaeresis for `ä`) split every Estonian word with a diacritic in two, and one zero-width space or
     * soft hyphen inside each word made a copy that looked identical share no word with its original.
     * [canonical] then makes both spellings the same word.
     */
    private val WORD = Regex("[\\p{L}\\p{N}_][\\p{L}\\p{N}\\p{M}\\p{Cf}_]*")

    private val FORMAT = Regex("\\p{Cf}")

    /** The word as compared: composed, without format characters, lowercased. */
    private fun canonical(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC).replace(FORMAT, "").lowercase()

    /**
     * One [WordTokenType] per distinct word rather than per occurrence. Types are compared by equality,
     * so no score changes; what changes is memory, since every occurrence used to carry its own type and
     * its own copy of the word - about 110 bytes a token, or some 500 MB for a cohort at the ceiling of
     * essays of up to 500,000 characters. The map is this call's own, so there is nothing for two threads
     * to share. Words have nothing to recover from, so nothing is ever [Tokenised.recovered].
     */
    override fun read(id: File, source: String, maxTokens: Int): Tokenised {
        val tokens = mutableListOf<Token>()
        val types = HashMap<String, WordTokenType>()
        source.lineSequence().forEachIndexed { index, line ->
            WORD.findAll(line).forEach { match ->
                if (tokens.size == maxTokens) throw SolutionTooLarge(maxTokens)
                val word = canonical(match.value)
                val column = match.range.first + 1
                tokens += Token(
                    types.getOrPut(word) { WordTokenType(word) }, id,
                    index + 1, column, index + 1, column + match.value.length, match.value.length,
                )
            }
        }
        val lastLine = source.count { it == '\n' } + 1
        tokens += Token(SharedTokenType.FILE_END, id, lastLine, 1, lastLine, 1, 0)
        return Tokenised(tokens, recovered = false)
    }
}

/**
 * A data class rather than an enum because the set is not known in advance; `equals`/`hashCode` are
 * what `TokenSequenceMapper`'s `HashMap<TokenType, Integer>` relies on, across submissions whose types
 * were made by different calls.
 */
private data class WordTokenType(val word: String) : TokenType {
    override fun getDescription(): String = word
}
