package core.ems.service.code.similarity

import de.jplag.SharedTokenType
import de.jplag.Token
import de.jplag.TokenType
import java.io.File
import java.text.Normalizer

/**
 * Prose compared word by word: for a Python exercise whose answers turn out not to be Python.
 *
 * One token type per distinct word. JPlag compares token *types*, so a single WORD type would make every
 * text identical to every other; with the word as the type, the tiling matches runs of the same words.
 * Words are letters, digits and underscores, lowercased.
 */
internal object TextFrontend : SimilarityFrontend {

    /**
     * A word, with the combining marks and invisible format characters inside it: an essay with `ä` written
     * as `a` and a combining diaeresis, or a zero-width space inside each word, would otherwise share no word
     * with its original. [canonical] then makes both spellings the same word.
     */
    private val WORD = Regex("[\\p{L}\\p{N}_][\\p{L}\\p{N}\\p{M}\\p{Cf}_]*")

    private val FORMAT = Regex("\\p{Cf}")

    private fun canonical(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC).replace(FORMAT, "").lowercase()

    override fun read(id: File, source: String, maxTokens: Int): Tokenised {
        val tokens = mutableListOf<Token>()
        // One type object per distinct word rather than per occurrence; types compare by equality.
        val types = HashMap<String, WordTokenType>()
        source.lineSequence().forEachIndexed { index, line ->
            WORD.findAll(line).forEach { match ->
                if (tokens.size == maxTokens) throw SolutionTooLarge(maxTokens)
                val word = canonical(match.value)
                val column = match.range.first + 1
                tokens += Token(types.getOrPut(word) { WordTokenType(word) }, id, index + 1, column, index + 1, column + match.value.length, match.value.length)
            }
        }
        val lastLine = source.count { it == '\n' } + 1
        tokens += Token(SharedTokenType.FILE_END, id, lastLine, 1, lastLine, 1, 0)
        return Tokenised(tokens, recovered = false)
    }
}

/** A data class, because JPlag maps token types through a HashMap across submissions. */
private data class WordTokenType(val word: String) : TokenType {
    override fun getDescription(): String = word
}
