package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.text.Normalizer

class TextFrontendTest {

    @Test
    fun `text mode is capped on words, and a solution at the cap is kept`() {
        SimilarityLanguage.TEXT.tokenise(File("t"), "one two three", 3)
        assertThrows<SolutionTooLarge> {
            SimilarityLanguage.TEXT.tokenise(File("t"), "one two three four", 3)
        }
    }

    @Test
    fun `a word is letters of any alphabet, so Estonian words are whole`() {
        // The essays this compares are mostly Estonian. An ASCII-only word split `pähkel` in two and
        // dropped the `õ` of `õun`, and every such word was a different token from its own spelling.
        val words = SimilarityLanguage.TEXT.tokenise(File("t"), "Õun ja pähkel, šokolaad.", 100).dropLast(1).map { it.type.description }
        assertEquals(listOf("õun", "ja", "pähkel", "šokolaad"), words)
    }

    @Test
    fun `a word spelled decomposed or with invisible characters in it is the same word`() {
        // Both look identical on screen. Decomposed, `pähkel` was two words; with a zero-width space or a
        // soft hyphen after each first letter, a copied essay shared no word with its original.
        val words = { text: String -> SimilarityLanguage.TEXT.tokenise(File("t"), text, 100).dropLast(1).map { it.type.description } }
        val essay = "Õun ja pähkel, šokolaad."
        assertEquals(words(essay), words(Normalizer.normalize(essay, Normalizer.Form.NFD)))
        for (invisible in listOf(Char(0x200B), Char(0x00AD), Char(0x2060))) {
            val laced = essay.split(" ").joinToString(" ") { it.take(1) + invisible + it.drop(1) }
            assertEquals(words(essay), words(laced), "U+%04X".format(invisible.code))
        }
    }

    @Test
    fun `every occurrence of a word shares one token type`() {
        // Nothing about the scores shows this; the heap does - 110 bytes a token without it.
        val tokens = SimilarityLanguage.TEXT.tokenise(File("t"), "the cat saw the other cat\nThe end", 100)
        val the = tokens.filter { it.type.description == "the" }
        assertEquals(3, the.size)
        the.forEach { assertSame(the.first().type, it.type) }
    }
}
