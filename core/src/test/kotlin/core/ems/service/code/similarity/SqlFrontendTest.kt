package core.ems.service.code.similarity

import de.jplag.SharedTokenType
import de.jplag.Token
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.random.Random

/**
 * The SQL frontend: what becomes a token, and what cannot hide one.
 *
 * Its whole design is a claim about which differences between two solutions count - names, values,
 * letter case and layout do not; keywords, operators and structure do - so most of this states both
 * sides of that claim on the same pair of inputs.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SqlFrontendTest {

    private fun tokens(source: String, maxTokens: Int = Int.MAX_VALUE): List<Token> =
        SimilarityLanguage.SQL.tokenise(File("s"), source, maxTokens)

    private fun types(source: String): List<String> = tokens(source).map { it.type.description }

    @Test
    fun `names, values, letter case, layout and comments are not tokens`() {
        val one = "SELECT t.eesnimi, COUNT(*) AS arv FROM tudeng t WHERE t.synniaasta > 2000 AND t.nimi = 'Mari' GROUP BY t.eesnimi;"
        val other = """
            select   s.first_name , count(*) as "Number Of"   -- renamed everything
            from students s
            where s.born > 1999.5 and s.nimi = 'Jüri'
            /* reformatted, /* nested */ commented */
            group by s.first_name;
        """.trimIndent()
        assertEquals(types(one), types(other))
    }

    @Test
    fun `keywords, operators and structure are tokens`() {
        val base = "SELECT a FROM t JOIN u ON t.x = u.y WHERE a > 1;"
        assertNotEquals(types(base), types(base.replace(">", "<")), "the comparison operator is not seen")
        assertNotEquals(types(base), types(base.replace("JOIN", "LEFT JOIN")), "the join kind is not seen")
        assertNotEquals(types(base), types(base.replace("a > 1", "(a > 1)")), "brackets are not seen")
        // One operator, two spellings.
        assertEquals(types("SELECT a FROM t WHERE a <> 1;"), types("SELECT a FROM t WHERE a != 1;"))
    }

    @Test
    fun `a dollar-quoted body is lexed as the code it is, between two delimiter tokens`() {
        val function = """
            CREATE FUNCTION f(x INTEGER) RETURNS INTEGER AS ${'$'}body${'$'}
            BEGIN
              IF x > 0 THEN RETURN x; END IF;
              RETURN 0;
            END;
            ${'$'}body${'$'} LANGUAGE plpgsql;
        """.trimIndent()
        val types = types(function)
        val body = types.subList(types.indexOf("DOLLAR_QUOTE") + 1, types.lastIndexOf("DOLLAR_QUOTE"))
        assertEquals(2, types.count { it == "DOLLAR_QUOTE" })
        assertEquals(listOf("BEGIN", "IF", "IDENTIFIER", ">", "NUMBER", "THEN", "RETURN"), body.take(7))
        assertEquals(listOf("LANGUAGE", "PLPGSQL", ";"), types.takeLast(4).dropLast(1), "the code after the body was lost")
        // The same body under the other spelling of the delimiter is the same tokens.
        assertEquals(types, types(function.replace("${'$'}body${'$'}", "$$")))
    }

    @Test
    fun `a quote inside a dollar-quoted string cannot swallow the rest of the file`() {
        // `$$It's$$` is a valid string literal, and runs. Lexed as code, its apostrophe opens a string
        // that never closes, and left to run it would make the rest of the file one STRING token -
        // out of the comparison for the price of one line. PostgreSQL ends the body at its delimiter
        // whatever is inside, and so does this.
        val hiding = "SELECT \$\$It's\$\$;\nSELECT a FROM t WHERE b > 1 ORDER BY a;"
        val types = types(hiding)
        assertEquals(
            listOf("SELECT", "IDENTIFIER", "FROM", "IDENTIFIER", "WHERE", "IDENTIFIER", ">", "NUMBER", "ORDER", "BY", "IDENTIFIER", ";"),
            types.subList(types.lastIndexOf("DOLLAR_QUOTE") + 2, types.size - 1),
        )
        // A name ends at the closing delimiter too, even with no space before it.
        assertEquals(listOf("SELECT", "DOLLAR_QUOTE", "IDENTIFIER", "DOLLAR_QUOTE", ";"), types("SELECT \$\$abc\$\$;").dropLast(1))
        // The same for a comment or a quoted name opened inside a body.
        for (opener in listOf("/*", "\"")) {
            val t = types("SELECT \$\$x $opener\$\$;\nSELECT a FROM t;")
            assertEquals(listOf("SELECT", "IDENTIFIER", "FROM", "IDENTIFIER", ";"), t.subList(t.size - 6, t.size - 1), "after $opener")
        }
    }

    @Test
    fun `strings end where PostgreSQL ends them`() {
        // '' is a quote inside a string, and so is \' inside an E'' string; neither ends it.
        assertEquals(
            listOf("SELECT", "STRING", ",", "STRING", ",", "STRING", ",", "IDENTIFIER", ";", SharedTokenType.FILE_END.description),
            types("SELECT 'it''s', E'it\\'s', U&'\\0041', \"Quoted \"\"name\"\"\";"),
        )
    }

    @Test
    fun `lines are counted through multi-line strings and comments`() {
        val source = "SELECT 'a\nb\nc',\n/* x\ny */ d FROM t;"
        val d = tokens(source).first { it.type.description == "IDENTIFIER" }
        assertEquals(5, d.startLine, "the name after them is on the wrong line")
        assertEquals(6, d.startColumn)
    }

    @Test
    fun `the cap counts tokens, not characters, and a solution at it is kept`() {
        val query = "SELECT a, b FROM t WHERE c = 1;"
        assertEquals(11, tokens(query).size - 1)
        tokens("-- " + "padding ".repeat(10_000) + "\n" + query, maxTokens = 11)
        assertThrows<SolutionTooLarge> { tokens("$query SELECT 1;", maxTokens = 11) }
        // One past the cap, not only well past it.
        assertThrows<SolutionTooLarge> { tokens("$query x", maxTokens = 11) }
    }

    @Test
    fun `every type is one canonical instance, whichever solution it came from`() {
        // The tiling maps types to numbers by equality, and a keyword made afresh per call would still
        // compare equal - this is about not carrying a copy per token, and about that equality being
        // the identity it looks like.
        val first = tokens("SELECT a FROM t;")
        val second = tokens("select b from u;")
        first.zip(second).forEach { (x, y) -> assertSame(x.type, y.type) }
    }

    /** Two queries after one line whose delimiter the lexer must recognise, or they are lost. */
    private val tail = "SELECT a FROM t WHERE b > 1 ORDER BY a;"
    private val tailTypes = listOf("SELECT", "IDENTIFIER", "FROM", "IDENTIFIER", "WHERE", "IDENTIFIER", ">", "NUMBER", "ORDER", "BY", "IDENTIFIER", ";")

    @Test
    fun `a dollar-quote tag outside ASCII is still a tag, so its quote cannot swallow the file`() {
        // PostgreSQL takes every byte of a multi-byte character for a letter (scan.l: dolq_start is
        // [A-Za-z\200-\377_]), so each of these is a valid tag and `SELECT $€$It's$€$;` runs. Read as
        // anything else, the apostrophe opens a string that runs to the end of the file - one line
        // that took a copied lab out of the comparison while it still passed.
        for (tag in listOf("€", "٣", "１", "😀", " ", "a·b")) {
            val types = types("SELECT \$$tag\$It's\$$tag\$;\n$tail")
            assertEquals(tailTypes, types.subList(types.size - 1 - tailTypes.size, types.size - 1), "after the tag $tag")
        }
    }

    @Test
    fun `a name is whatever PostgreSQL takes for one, so renaming with any characters changes nothing`() {
        // The same rule for names: any character past ASCII is a letter to PostgreSQL, a zero-width
        // space and an emoji included. Split or skipped here, a rename into such names - which the diff
        // shows exactly as the plain one - changed the tokens and so the score.
        val plain = "SELECT a1.nimi, b.x FROM klient a1 JOIN tellimus b ON b.k = a1.id WHERE a1.id > 1;"
        for (renamed in listOf("a​1", "😀", "x€1", "𝑎", "a b", "õun")) {
            assertEquals(types(plain), types(plain.replace("a1", renamed)), "renamed to ${renamed.map { it.code.toString(16) }}")
        }
    }

    @Test
    fun `spellings of one type are one token`() {
        // A structure test sees these as the same column, so a copy may respell every one of them;
        // with a type every few tokens of DDL, each respelling broke a run.
        val spellings = listOf(
            listOf("INTEGER", "INT", "int4"), listOf("SMALLINT", "INT2"), listOf("BIGINT", "INT8"),
            listOf("BOOLEAN", "BOOL"), listOf("NUMERIC", "DECIMAL", "DEC"), listOf("REAL", "FLOAT4"),
            listOf("DOUBLE PRECISION", "FLOAT8", "FLOAT"), listOf("VARCHAR", "CHARACTER VARYING", "char\n  varying"),
            listOf("CHAR", "CHARACTER"), listOf("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE"),
            listOf("TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE"), listOf("SERIAL", "SERIAL4"),
        )
        for (same in spellings) {
            val expected = types("CREATE TABLE t (a ${same.first()}(5), b INTEGER);")
            for (other in same.drop(1)) assertEquals(expected, types("CREATE TABLE t (a $other(5), b INTEGER);"), "$other")
        }
        assertNotEquals(types("CREATE TABLE t (a INTEGER);"), types("CREATE TABLE t (a TEXT);"), "a different type is not seen")
    }

    @Test
    fun `a built-in function's name is a keyword only where it is called`() {
        // Aliases and PL/pgSQL variables are the copier's to rename, and `count` or `sum` is an obvious
        // name for one; as keywords everywhere, translating them changed the tokens.
        assertEquals(
            types("SELECT COUNT(x) AS count, SUM (y) AS sum FROM t ORDER BY count, sum;"),
            types("SELECT COUNT(x) AS arv, SUM (y) AS kokku FROM t ORDER BY arv, kokku;"),
        )
        assertNotEquals(types("SELECT COUNT(x) FROM t;"), types("SELECT SUM(x) FROM t;"), "the function called is not seen")
    }

    @Test
    fun `a routine body written as a string is lexed as code, like a dollar-quoted one`() {
        // `AS '…'` is PostgreSQL's own spelling of a body - its documentation's examples use it - and as
        // one STRING token two different honest bodies under an exercise's fixed signature scored 100%.
        val header = "CREATE FUNCTION f(x integer) RETURNS integer AS"
        assertEquals(types("$header \$\$SELECT x + 1\$\$ LANGUAGE sql;"), types("$header 'SELECT x + 1' LANGUAGE sql;"))
        assertNotEquals(types("$header 'SELECT x + 1' LANGUAGE sql;"), types("$header 'SELECT x * 2 - 1' LANGUAGE sql;"))
        // Quotes inside such a body are doubled, and a string in it is still one STRING.
        assertEquals(
            types("DO \$\$BEGIN RAISE NOTICE 'it''s'; END\$\$;\n$tail"),
            types("DO 'BEGIN RAISE NOTICE ''it''''s''; END';\n$tail"),
        )
        // Every string form a body may take, and the place after `DO LANGUAGE lang` it may stand.
        for (form in listOf("E'SELECT x + 1'", "U&'SELECT x + 1'")) {
            assertEquals(types("$header \$\$SELECT x + 1\$\$ LANGUAGE sql;"), types("$header $form LANGUAGE sql;"), form)
        }
        assertNotEquals(types("$header E'SELECT x + 1' LANGUAGE sql;"), types("$header E'SELECT x * 2 - 1' LANGUAGE sql;"))
        // An escaped quote does not end an E'' body: ending it there left a quote that hid the rest.
        val tailed = types("$header E'SELECT 1; -- it\\'s' LANGUAGE sql;\n$tail")
        assertEquals(tailTypes, tailed.dropLast(1).takeLast(tailTypes.size), "$tailed")
        assertEquals(
            types("DO LANGUAGE plpgsql \$\$BEGIN RAISE NOTICE 'x'; END\$\$;\n$tail"),
            types("DO LANGUAGE plpgsql 'BEGIN RAISE NOTICE ''x''; END';\n$tail"),
        )
    }

    @Test
    fun `a carriage return ends a line comment and a line, as it does for PostgreSQL`() {
        // PostgreSQL's comment is `--` then [^\n\r]*: a lone CR ends it. Read as running to the next LF,
        // `-- x<CR>` hid everything up to it.
        val types = types("-- x\rSELECT b FROM u;\n$tail")
        assertEquals(listOf("SELECT", "IDENTIFIER", "FROM", "IDENTIFIER", ";") + tailTypes, types.dropLast(1))
        val lab = "SELECT a\nFROM t -- c\nWHERE b = 'x\ny';\nSELECT 1;"
        assertEquals(types(lab), types(lab.replace("\n", "\r")))
        assertEquals(types(lab), types(lab.replace("\n", "\r\n")))
        val lines = { source: String -> tokens(source).map { it.startLine } }
        assertEquals(lines(lab), lines(lab.replace("\n", "\r")), "a lone CR is not counted as a line end")
        assertEquals(lines(lab), lines(lab.replace("\n", "\r\n")))
    }

    @Test
    fun `optional words and other spellings of one operator are the same tokens`() {
        assertEquals(types("SELECT a FROM t JOIN u ON t.x = u.y;"), types("SELECT a FROM t INNER JOIN u ON t.x = u.y;"))
        assertEquals(types("SELECT a FROM t LEFT JOIN u ON t.x = u.y;"), types("SELECT a FROM t LEFT OUTER JOIN u ON t.x = u.y;"))
        assertEquals(types("SELECT a FROM t WHERE a LIKE 'x%';"), types("SELECT a FROM t WHERE a ~~ 'x%';"))
        assertEquals(types("SELECT a FROM t WHERE a NOT ILIKE 'x%';"), types("SELECT a FROM t WHERE a !~~* 'x%';"))
        // Empty statements are layout: a thousand of them made a copy TOO_REPETITIVE, out of the comparison.
        assertEquals(types("SELECT 1;"), types("SELECT 1;;;;\n;"))
        assertEquals(types("FOR i IN 1 .. 3 LOOP"), types("FOR i IN 1..3 LOOP"))
        // Constants separated only by a line break are one constant to PostgreSQL.
        assertEquals(types("SELECT 'abcdef';"), types("SELECT 'abc'\n  'def';"))
        // The continued part of an E'' string is still one: read as plain, its escaped quote ended it, and
        // the quote after that opened a string that hid the rest of the file.
        assertEquals(types("SELECT 'x';\n$tail"), types("SELECT E'a'\n'b\\'c';\n$tail"))
        assertEquals(types("SELECT nimi FROM t;"), types("SELECT U&\"nimi\" FROM t;"))
    }

    @Test
    fun `many unclosed dollar quotes are lexed in linear time`() {
        // Each opener used to search the rest of the source for its delimiter, so distinct openers that
        // never close cost their number times the file's length.
        // At this size that was seconds; linear, it is tens of milliseconds.
        val source = (0 until 60_000).joinToString(" ") { "\$a$it\$" } + " /* " + "\$x".repeat(150_000) + " */"
        val started = System.nanoTime()
        val tokens = tokens(source)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(60_000 + 1, tokens.size)
        assertTrue(tookMs < 2_000, "took $tookMs ms")
    }

    @Test
    fun `no input makes it fail, and every result ends in FILE_END`() {
        // A lexer that can throw is one submission away from being a CANNOT_PARSE nobody meant. Random
        // text heavy in the characters that open things - quotes, dollars, comment starts - that never
        // close as they should, and in the ones PostgreSQL reads differently from a naive lexer.
        val random = Random(20260928)
        val alphabet = "ab1 \n\r'\"\$-/*()<>=!:;.,_E\\€٣​~&U\uD83D"
        repeat(2_000) {
            val source = String(CharArray(random.nextInt(0, 80)) { alphabet[random.nextInt(alphabet.length)] })
            val result = tokens(source)
            assertSame(SharedTokenType.FILE_END, result.last().type, "no FILE_END for ${source.replace("\n", "\\n")}")
            assertTrue(result.dropLast(1).none { it.type == SharedTokenType.FILE_END })
            assertTrue(result.all { it.startLine >= 1 && it.startColumn >= 1 }, "a position before the start in $source")
        }
    }
}
