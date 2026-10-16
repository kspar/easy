package core.ems.service.code.similarity

import de.jplag.SharedTokenType
import de.jplag.Token
import de.jplag.TokenType
import java.io.File

/**
 * SQL, which JPlag has no module for, tokenised by a lexer of core's own.
 *
 * Lexed rather than parsed, deliberately. Lahendus grades SQL on PostgreSQL (Silmused), so what students
 * write is PostgreSQL's dialect - DDL, queries, and the PL/pgSQL of functions, procedures and triggers -
 * often with the syntax errors of work in progress. A grammar would refuse or mis-recover on exactly
 * those, and the one complete PostgreSQL grammar there is (grammars-v4's) is thousands of ANTLR rules,
 * with the prediction costs the Python frontend has to budget for. A lexer cannot fail, is linear, and
 * has nothing to nest.
 *
 * What becomes a token follows JPlag's rule for code - structure, not names:
 *
 *  - each keyword, operator and punctuation mark is a type of its own, so `<` and `>` differ, and so do
 *    `LEFT JOIN` and `JOIN`. Spellings PostgreSQL treats as one are one type: `!=` and `<>`, `~~` and
 *    `LIKE`, `INT` and `INTEGER`, `CHARACTER VARYING` and `VARCHAR`, `INNER JOIN` and `JOIN` - each a
 *    free respelling for a copier, and on DDL, where a type comes every few tokens, one that broke every
 *    run it touched;
 *  - every identifier is one type, quoted or not, so renaming an alias, a column or a table does not
 *    lower a score - on an exercise's fixed schema those are the only names a copier can change;
 *  - every literal is one type per kind, so changing a value does not either;
 *  - comments, whitespace, letter case and empty statements are not tokens at all.
 *
 * Which words are keywords is a list, [KEYWORDS]: SQL's and PL/pgSQL's structural words and PostgreSQL's
 * common types, and [FUNCTIONS], the built-in functions, which are keywords only where they are called -
 * followed by `(` - since `count` or `sum` is also the obvious name for the alias of one. A word the lists
 * do not have is a name. That is a choice about what renaming can hide, not a claim about PostgreSQL's
 * grammar, so the lists leave out words that are far more often columns than keywords - `name`, `value`,
 * `year` - and a column called `date` is simply the keyword that every copy of that schema also has.
 *
 * What is a name, a delimiter or a line end is PostgreSQL's own rule rather than Unicode's, because each
 * disagreement is somewhere a copy can hide. PostgreSQL's scanner works on bytes and takes every byte of
 * a multi-byte character for a letter, so to it a zero-width space, an emoji or `€` is part of a name, and
 * `$€$` a dollar-quote delimiter: read as Unicode reads them, a rename into such names - shown by the page
 * exactly as the plain one - split or dropped tokens, and one valid `SELECT $€$It's$€$;` hid everything
 * after it (see [SqlLexer]). Likewise its line comment ends at a CR as well as an LF.
 *
 * The body of a function, a procedure, a trigger or a `DO` block - dollar-quoted, `$$ … $$`, or written as
 * a string, `AS '…'` - is lexed as code rather than as the one string literal it is to PostgreSQL. That is
 * usually most of a submission that has one, and as one token two different honest bodies under an
 * exercise's fixed signature scored 100%. Each delimiter is a token of its own, the same one for both
 * spellings. (A dollar-quoted string that is only a string gets its words lexed as names, which costs
 * nothing but a few tokens.)
 *
 * Read the scores knowing that on one schema, independently written correct answers to a short query
 * share most of their tokens, because there are few ways to write it; see the calibration in
 * doc/jplag-integration.md.
 */
internal object SqlFrontend : SimilarityFrontend {

    /**
     * 12, twice Python's, because a lexical token is a finer thing than one of JPlag's: `FROM a JOIN b ON
     * a.x = b.y` is twelve of these where Python's grammar would make a statement or two. Calibrated on a
     * synthetic cohort of the exercises Silmused checks - a query, a join, DDL, a view, a function, a
     * trigger - each with honest solutions and disguised copies (the sweep is in
     * doc/jplag-integration.md). A copy with its names changed and its layout redone scores 100% at every
     * value; one with small structural edits too scored 67% over a whole lab here, against at most 38%
     * for any two honest labs - where at 8 honest labs reached 52%, and at 15 a one-query answer of the
     * shortest kind starts being too small to compare at all.
     */
    override val minimumTokenMatch = 12

    /** A lexer has nothing to recover from: what it does not recognise it skips, as PostgreSQL's would report it. */
    override fun read(id: File, source: String, maxTokens: Int): Tokenised {
        val tokens = ArrayList<Token>()
        val lexer = SqlLexer(source) { type, start, end, length ->
            if (tokens.size == maxTokens) throw SolutionTooLarge(maxTokens)
            tokens += Token(type, id, start.line, start.column, end.line, end.column, length)
        }
        lexer.run()
        tokens += Token(SharedTokenType.FILE_END, id, lexer.lines, 1, lexer.lines, 1, 0)
        return Tokenised(tokens, recovered = false)
    }
}

/** Where a token starts or ends, 1-based as JPlag's tokens are. */
private data class Position(val line: Int, val column: Int)

/**
 * One kind of SQL token. Every instance is one of the canonical ones below, made once when the class
 * loads and never after, so types compare by identity across every thread and every cohort.
 */
internal class SqlTokenType private constructor(private val description: String) : TokenType {
    override fun getDescription(): String = description
    override fun toString(): String = description

    companion object {
        val IDENTIFIER = SqlTokenType("IDENTIFIER")
        val STRING = SqlTokenType("STRING")
        val NUMBER = SqlTokenType("NUMBER")

        /** A positional parameter, `$1`. */
        val PARAMETER = SqlTokenType("PARAMETER")

        /** Either end of a body lexed as code, dollar-quoted or string-quoted. */
        val DOLLAR_QUOTE = SqlTokenType("DOLLAR_QUOTE")

        private val keywords: Map<String, SqlTokenType> = (KEYWORDS + FUNCTIONS).associateWith { SqlTokenType(it.uppercase()) }
        private val symbols: Map<String, SqlTokenType> = (OPERATORS + PUNCTUATION).associateWith { SqlTokenType(it) }

        /** The keyword's type, for a word already lowercased and canonical, or null for a name. */
        fun keyword(word: String): SqlTokenType? = keywords[word]

        fun symbol(text: String): SqlTokenType = symbols.getValue(text)

        /** What a string right after them is: the body of a routine, written the other way to `$$`. */
        val BODY_INTRODUCERS: Set<SqlTokenType> = setOf(keywords.getValue("as"), keywords.getValue("do"))

        val DO = keywords.getValue("do")
        val LANGUAGE = keywords.getValue("language")

        val SEMICOLON = symbols.getValue(";")
        val NOT = keywords.getValue("not")
        val LIKE = keywords.getValue("like")
        val ILIKE = keywords.getValue("ilike")
    }
}

/**
 * The lexer: one pass over the source, emitting each token with its line, column and length. Knows
 * PostgreSQL's lexical rules where they decide where a token ends - nested block comments, `''` inside
 * strings, backslash escapes in `E'…'` strings, `""` inside quoted identifiers, dollar-quote tags, what
 * counts as a letter and a line end (see [SqlFrontend]) - and never fails: what it does not recognise it
 * skips.
 *
 * Inside a body lexed as code, its closing delimiter ends the body wherever it appears, even part way
 * through what would otherwise be a string, a comment or a name - which is PostgreSQL's own rule, since
 * to it the body is text until the delimiter. It is also what keeps one quote mark from hiding a file: a
 * valid `SELECT $$It's$$;` opens a `'` string that never closes, and left to run it would swallow
 * everything after it into one STRING token, taking the rest of a submission out of the comparison while
 * it still ran. Every construct is scanned no further than [bound].
 *
 * A body written as a string, `AS '…'`, is the same thing one level of quoting down: inside it every
 * quote mark is doubled, so there `''` is what opens and closes a string, and `''''` is a quote inside
 * one. [quote] is the quote mark at the current depth.
 */
private class SqlLexer(private val source: String, private val emit: (SqlTokenType, Position, Position, Int) -> Unit) {
    private var pos = 0
    private var line = 1
    private var lineStart = 0

    /** How many lines the source has, once [run] is done - counting LF, CRLF and a lone CR, as PostgreSQL does. */
    val lines: Int get() = line

    /** The last token emitted, and where it ended: what the rules that look back need. */
    private var last: SqlTokenType? = null
    private var lastEnd = 0

    /** The two tokens before [last], for `DO LANGUAGE lang '…'`, whose body comes after the language. */
    private var secondLast: SqlTokenType? = null
    private var thirdLast: SqlTokenType? = null

    /** Whether the last STRING was an `E'…'` one: a continuation of it keeps its backslash escapes, as in PostgreSQL. */
    private var lastStringEscapes = false

    /** A body that is open at [pos]: its closing delimiter, where that closes it, and whether it is string-quoted. */
    private class Body(val delimiter: String, val closesAt: Int, val stringQuoted: Boolean)

    /** The bodies open at [pos], innermost last. */
    private val open = ArrayDeque<Body>()

    /** The quote mark a string literal is written with here: `'`, doubled for each string-quoted body open. */
    private var quote = "'"

    /** How far the construct starting at [pos] may extend: the innermost body's closing delimiter, or the end. */
    private fun bound(): Int = open.lastOrNull()?.closesAt ?: source.length

    /** Every dollar-quote delimiter's positions, so that finding where one closes costs no rescan; see [DelimiterIndex]. */
    private val delimiters by lazy { DelimiterIndex(source) }

    fun run() {
        // A byte-order mark is not code. (Anywhere else U+FEFF is what PostgreSQL makes of any other
        // character past ASCII: part of a name.)
        if (source.startsWith('﻿')) {
            pos = 1
            lineStart = 1
        }
        while (pos < source.length) {
            val innermost = open.lastOrNull()
            if (innermost != null && pos >= innermost.closesAt) {
                close(innermost)
                continue
            }
            val c = source[pos]
            val next = source.getOrNull(pos + 1)
            when {
                c == '\n' -> newline()
                c == '\r' -> if (next == '\n') pos++ else newline()
                isSpace(c) -> pos++
                c == '-' && next == '-' -> skipLineComment()
                c == '/' && next == '*' -> skipBlockComment()
                source.startsWith(quote, pos) -> string(pos, pos, backslashEscapes = false)
                (c == 'e' || c == 'E') && source.startsWith(quote, pos + 1) -> string(pos, pos + 1, backslashEscapes = true)
                c in "bBxXnN" && source.startsWith(quote, pos + 1) -> string(pos, pos + 1, backslashEscapes = false)
                (c == 'u' || c == 'U') && next == '&' && source.startsWith(quote, pos + 2) -> string(pos, pos + 2, backslashEscapes = false)
                (c == 'u' || c == 'U') && next == '&' && source.getOrNull(pos + 2) == '"' -> quotedIdentifier(pos, pos + 2)
                c == '"' -> quotedIdentifier(pos, pos)
                c == '$' -> dollar()
                isDigit(c) || (c == '.' && isDigit(next)) -> number()
                isIdentifierStart(c) -> word()
                else -> symbol()
            }
        }
    }

    private fun newline() {
        pos++
        line++
        lineStart = pos
    }

    /** Moves to [end], keeping [line] and [lineStart] right for whatever line ends were in between. */
    private fun consumeTo(end: Int) {
        while (pos < end) {
            val c = source[pos]
            if (c == '\n' || (c == '\r' && source.getOrNull(pos + 1) != '\n')) newline() else pos++
        }
    }

    private fun column(at: Int) = at - lineStart + 1

    private fun emitFrom(type: SqlTokenType, start: Int, startLine: Int, startColumn: Int) {
        emit(type, Position(startLine, startColumn), Position(line, column(pos)), pos - start)
        thirdLast = secondLast
        secondLast = last
        last = type
        lastEnd = pos
    }

    /** To the end of the line, which in PostgreSQL is a CR as well as an LF: `--` then `[^\n\r]*`. */
    private fun skipLineComment() {
        val limit = bound()
        var i = pos + 2
        while (i < limit && source[i] != '\n' && source[i] != '\r') i++
        pos = i
    }

    /** PostgreSQL's block comments nest, unlike the SQL standard's; an unterminated one runs to [bound]. */
    private fun skipBlockComment() {
        val limit = bound()
        var depth = 0
        var i = pos
        while (i < limit) {
            when {
                source.startsWith("/*", i) -> { depth++; i += 2 }
                source.startsWith("*/", i) -> { depth--; i += 2; if (depth == 0) break }
                else -> i++
            }
        }
        consumeTo(minOf(i, limit))
    }

    /**
     * A string literal starting at [start], its opening quote at [quoteAt]; the quote doubled is a quote
     * inside it. Right after `AS` or `DO` it is a routine's body instead, and is lexed as code.
     *
     * Two constants separated only by whitespace with a line break in it are one constant to PostgreSQL,
     * so they are one token here, rather than a way to add one wherever a string is split. The continued
     * part keeps the escapes of the part it continues: read as a plain string, `E'a'` then `'b\'c'` on the
     * next line ended at `\'`, and the quote after it opened a string that hid the rest of the file.
     *
     * A body may be any string form a routine accepts as its body - plain, `E'…'` or `U&'…'` - and comes
     * after `AS`, after `DO`, or after `DO LANGUAGE lang`. Only the plain form right after `AS` or `DO`
     * was a body here, so the others stayed one STRING, and two different bodies scored 100%.
     */
    private fun string(start: Int, quoteAt: Int, backslashEscapes: Boolean) {
        val bodyForm = quoteAt == start || source[start] in "eEuU"
        val afterDoLanguage = thirdLast === SqlTokenType.DO && secondLast === SqlTokenType.LANGUAGE && last !== SqlTokenType.LANGUAGE
        if (bodyForm && (last in SqlTokenType.BODY_INTRODUCERS || afterDoLanguage)) return openStringBody(start, quoteAt, backslashEscapes)
        val continuation = quoteAt == start && last === SqlTokenType.STRING && onlyLineBreakBetween(lastEnd, start)
        val escapes = if (continuation) lastStringEscapes else backslashEscapes
        val startLine = line
        val startColumn = column(start)
        val limit = bound()
        val doubled = quote + quote
        var i = quoteAt + quote.length
        while (i < limit) {
            when {
                escapes && source[i] == '\\' -> i += 2
                source.startsWith(doubled, i) -> i += doubled.length
                source.startsWith(quote, i) -> { i += quote.length; break }
                else -> i++
            }
        }
        consumeTo(minOf(i, limit))
        if (continuation) lastEnd = pos else emitFrom(SqlTokenType.STRING, start, startLine, startColumn)
        lastStringEscapes = escapes
    }

    private fun onlyLineBreakBetween(from: Int, to: Int): Boolean =
        (from until to).all { isSpaceOrLineEnd(source[it]) } && (from until to).any { source[it] == '\n' || source[it] == '\r' }

    /**
     * `AS '…'` or `DO '…'`: the same body a `$$` would quote, written the older way PostgreSQL's own
     * documentation still uses. Opened like a dollar-quoted one, with its quote mark for the delimiter,
     * so both spellings of one body are the same tokens.
     *
     * An `E'…'` body ends where its escapes say: read without them, `\'` ended it early, and the quote
     * left over opened a string that hid the rest of the file.
     */
    private fun openStringBody(start: Int, quoteAt: Int, backslashEscapes: Boolean) {
        val limit = bound()
        val doubled = quote + quote
        var i = quoteAt + quote.length
        while (i < limit) {
            when {
                backslashEscapes && source[i] == '\\' -> i += 2
                source.startsWith(doubled, i) -> i += doubled.length
                source.startsWith(quote, i) -> break
                else -> i++
            }
        }
        val startColumn = column(start)
        pos = quoteAt + quote.length
        emitFrom(SqlTokenType.DOLLAR_QUOTE, start, line, startColumn)
        open.addLast(Body(quote, minOf(i, limit), stringQuoted = true))
        quote = doubled
    }

    /** `"…"` or `U&"…"`, its opening quote at [quoteAt]: a name, whatever is inside. */
    private fun quotedIdentifier(start: Int, quoteAt: Int) {
        val startLine = line
        val startColumn = column(start)
        val limit = bound()
        var i = quoteAt + 1
        while (i < limit) {
            when {
                source[i] == '"' && source.getOrNull(i + 1) == '"' -> i += 2
                source[i] == '"' -> { i++; break }
                else -> i++
            }
        }
        consumeTo(minOf(i, limit))
        emitFrom(SqlTokenType.IDENTIFIER, start, startLine, startColumn)
    }

    /** `$1`, a parameter; `$tag$`, a dollar quote opening; anything else, a lone `$`, skipped. */
    private fun dollar() {
        val start = pos
        val startColumn = column(start)
        var i = pos + 1
        if (isDigit(source.getOrNull(i))) {
            while (i < source.length && isDigit(source[i])) i++
            pos = i
            return emitFrom(SqlTokenType.PARAMETER, start, line, startColumn)
        }
        val limit = bound()
        while (i < limit && isTagPart(source[i])) i++
        if (i >= limit || source[i] != '$') {
            pos++
            return
        }
        val delimiter = source.substring(start, i + 1)
        pos = i + 1
        // An inner body cannot outlast the one it is inside, which PostgreSQL does not even see.
        val closesAt = delimiters.next(delimiter, pos)?.takeIf { it <= limit } ?: limit
        open.addLast(Body(delimiter, closesAt, stringQuoted = false))
        emitFrom(SqlTokenType.DOLLAR_QUOTE, start, line, startColumn)
    }

    /**
     * At [body]'s closing position. Its delimiter is there, unless it never closed inside the body around
     * it - then it just ends, and the outer one closes here instead.
     */
    private fun close(body: Body) {
        open.removeLast()
        if (body.stringQuoted) quote = quote.substring(quote.length / 2)
        if (source.startsWith(body.delimiter, pos)) {
            val start = pos
            val startColumn = column(start)
            pos += body.delimiter.length
            emitFrom(SqlTokenType.DOLLAR_QUOTE, start, line, startColumn)
        }
    }

    /** Integers, decimals and exponents, with PostgreSQL 16's underscores and hexadecimal: one NUMBER. */
    private fun number() {
        val start = pos
        val startColumn = column(start)
        val limit = bound()
        var i = pos
        while (i < limit) {
            val d = source[i]
            val exponentSign = (d == '+' || d == '-') && i > start && source[i - 1] in "eE" && isDigit(source.getOrNull(i + 1))
            // `1..3` is a PL/pgSQL range, so a point followed by another is not a decimal point.
            val decimalPoint = d == '.' && source.getOrNull(i + 1) != '.'
            if (isAsciiLetterOrDigit(d) || d == '_' || decimalPoint || exponentSign) i++ else break
        }
        pos = i
        emitFrom(SqlTokenType.NUMBER, start, line, startColumn)
    }

    private fun word() {
        val start = pos
        val startLine = line
        val startColumn = column(start)
        val limit = bound()
        var i = pos + 1
        while (i < limit && isIdentifierPart(source[i])) i++
        val word = source.substring(start, i).lowercase()
        // A type of several words - DOUBLE PRECISION, CHARACTER VARYING - is the one token its other
        // spelling is.
        for ((rest, canonical) in PHRASES[word].orEmpty()) {
            val end = phraseEnd(i, rest, limit) ?: continue
            consumeTo(end)
            return emitFrom(SqlTokenType.keyword(canonical)!!, start, startLine, startColumn)
        }
        pos = i
        // INNER and OUTER change nothing a join does: JOIN is INNER JOIN, LEFT JOIN is LEFT OUTER JOIN.
        if (word in OPTIONAL_WORDS) return
        val canonical = SYNONYMS[word] ?: word
        val keyword = SqlTokenType.keyword(canonical)?.takeUnless { canonical in FUNCTIONS && nextNonBlank(i, limit) != '(' }
        emitFrom(keyword ?: SqlTokenType.IDENTIFIER, start, startLine, startColumn)
    }

    /** Where the words [rest] end, if they follow [from] separated by whitespace only; otherwise null. */
    private fun phraseEnd(from: Int, rest: List<String>, limit: Int): Int? {
        var i = from
        for (next in rest) {
            val gap = i
            while (i < limit && isSpaceOrLineEnd(source[i])) i++
            if (i == gap || i + next.length > limit || !source.regionMatches(i, next, 0, next.length, ignoreCase = true)) return null
            i += next.length
            if (i < limit && isIdentifierPart(source[i])) return null
        }
        return i
    }

    private fun nextNonBlank(from: Int, limit: Int): Char? {
        var i = from
        while (i < limit && isSpaceOrLineEnd(source[i])) i++
        return if (i < limit) source[i] else null
    }

    private fun symbol() {
        val start = pos
        val text = SYMBOLS_LONGEST_FIRST.firstOrNull { source.startsWith(it, pos) && pos + it.length <= bound() }
        if (text == null) {
            pos++
            return
        }
        pos += text.length
        val column = column(start)
        when (text) {
            // The operator spellings of pattern matching are the words they stand for.
            "~~" -> emitFrom(SqlTokenType.LIKE, start, line, column)
            "~~*" -> emitFrom(SqlTokenType.ILIKE, start, line, column)
            "!~~" -> { emitFrom(SqlTokenType.NOT, start, line, column); emitFrom(SqlTokenType.LIKE, start, line, column) }
            "!~~*" -> { emitFrom(SqlTokenType.NOT, start, line, column); emitFrom(SqlTokenType.ILIKE, start, line, column) }
            "!=" -> emitFrom(SqlTokenType.symbol("<>"), start, line, column)
            // An empty statement is layout. A thousand of them, a valid no-op, made a copy TOO_REPETITIVE.
            ";" -> if (last !== SqlTokenType.SEMICOLON) emitFrom(SqlTokenType.SEMICOLON, start, line, column)
            else -> emitFrom(SqlTokenType.symbol(text), start, line, column)
        }
    }
}

/**
 * Where each dollar-quote delimiter occurs in a source, found in one pass.
 *
 * Finding a body's end by searching the rest of the source from each opener was quadratic in openers that
 * never close: forty thousand distinct ones took seconds for one submission. Inside a body PostgreSQL
 * finds its end by reading a delimiter at each `$` - `$` tag `$`, the tag as long as it goes - and
 * comparing it with the opening one, which is what this indexes: every `$` that starts such a delimiter,
 * under that delimiter.
 */
private class DelimiterIndex(source: String) {
    private val positions: Map<String, IntArray>

    init {
        val found = HashMap<String, MutableList<Int>>()
        var at = source.indexOf('$')
        while (at >= 0) {
            var i = at + 1
            if (i < source.length && !isDigit(source[i])) {
                while (i < source.length && isTagPart(source[i])) i++
                if (i < source.length && source[i] == '$') found.getOrPut(source.substring(at, i + 1)) { mutableListOf() } += at
            }
            at = source.indexOf('$', at + 1)
        }
        positions = found.mapValues { it.value.toIntArray() }
    }

    /** The first position at or after [from] where [delimiter] occurs, or null. */
    fun next(delimiter: String, from: Int): Int? {
        val at = positions[delimiter] ?: return null
        val i = at.binarySearch(from).let { if (it < 0) -it - 1 else it }
        return at.getOrNull(i)
    }
}

// PostgreSQL's character classes (scan.l), over characters: it reads bytes, and every byte of a character
// past ASCII is in `ident_start`, `ident_cont`, `dolq_start` and `dolq_cont` alike. Digits are ASCII only.
private fun isDigit(c: Char?) = c != null && c in '0'..'9'
private fun isAsciiLetterOrDigit(c: Char) = isDigit(c) || c in 'a'..'z' || c in 'A'..'Z'
private fun isIdentifierStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c.code >= 0x80

// `$` inside a name is PostgreSQL's (`a$b`); a dollar-quote tag may not have one.
private fun isIdentifierPart(c: Char) = isIdentifierStart(c) || isDigit(c) || c == '$'
private fun isTagPart(c: Char) = isIdentifierStart(c) || isDigit(c)

/** `space` in scan.l, less its line ends: anything else past ASCII is a letter there, a no-break space too. */
private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\u000B' || c == '\u000C'
private fun isSpaceOrLineEnd(c: Char) = isSpace(c) || c == '\n' || c == '\r'

private val OPERATORS = listOf(
    "->>", "#>>", "!~~*", "~~*", "!~~", "!~*", "<>", "!=", "<=", ">=", "||", "::", "->", "#>", "@>", "<@",
    "&&", ":=", "=>", "<<", ">>", "~~", "~*", "!~", "^@", "@@",
    "+", "-", "*", "/", "%", "^", "<", ">", "=", "~", "!", "@", "#", "&", "|", "?",
)

/** `..` is PL/pgSQL's range, `FOR i IN 1..3`: one token however it is spaced. */
private val PUNCTUATION = listOf("..", "(", ")", "[", "]", ",", ";", ".", ":")

private val SYMBOLS_LONGEST_FIRST = (OPERATORS + PUNCTUATION).sortedByDescending { it.length }

/** Lowercase. What is and is not here is explained on [SqlFrontend]. */
private val KEYWORDS: Set<String> = """
    add all alter analyze and any array as asc asymmetric authorization begin between both by cascade
    case cast check collate column commit concurrently constraint continue copy create cross cube current
    current_date current_time current_timestamp current_user cursor database deallocate declare default
    deferrable deferred delete desc distinct do domain drop each else end escape except exclude execute
    exists explain extension false fetch filter first following for foreign from full function generated
    grant group grouping having identity if ilike in include index inherits initially inout insert
    instead intersect into is isnull join key language last lateral leading left like limit local lock
    materialized merge natural new no not nothing notnull null nulls of off offset old on only or order
    ordinality out over overlaps owned partition placing preceding primary procedure range recursive
    references referencing refresh rename replace restrict returning revoke right rollback rollup row rows
    savepoint schema select sequence session_user set setof similar some symmetric table tablespace temp
    temporary then ties to trailing transaction trigger true truncate unbounded union unique unlogged until
    update using vacuum values variadic verbose view when where window with within without

    call called returns return plpgsql immutable stable volatile strict security definer invoker leakproof
    parallel cost elsif elseif loop while foreach exit reverse slice raise notice exception perform get
    diagnostics found query record rowtype type assert info warning log debug sqlstate sqlerrm others
    tg_op tg_name tg_table_name tg_when tg_level before after statement

    bigint bigserial bit boolean bytea char date double float8 integer interval json jsonb money numeric
    precision real serial smallint smallserial text time timestamp timestamptz timetz uuid varbit varchar
    varying xml zone
""".split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

/** PostgreSQL's built-in functions: keywords where they are called, names anywhere else. */
private val FUNCTIONS: Set<String> = """
    abs age array_agg array_length avg bool_and bool_or btrim ceil ceiling char_length coalesce concat
    concat_ws count cume_dist currval date_part date_trunc dense_rank every extract first_value floor
    format generate_series greatest initcap jsonb_agg jsonb_build_object json_agg json_build_object lag
    last_value lead least length lower lpad ltrim make_date max md5 min mod nextval now nth_value ntile
    nullif percent_rank position power random rank regexp_match regexp_matches regexp_replace round
    row_number rpad rtrim setval sign split_part sqrt string_agg strpos substr substring sum to_char
    to_date to_json to_jsonb to_number to_timestamp trim trunc unnest upper
""".split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

/** Other spellings of one type, to the spelling [KEYWORDS] has. */
private val SYNONYMS: Map<String, String> = mapOf(
    "int" to "integer", "int4" to "integer", "int2" to "smallint", "int8" to "bigint",
    "serial4" to "serial", "serial2" to "smallserial", "serial8" to "bigserial",
    "bool" to "boolean", "dec" to "numeric", "decimal" to "numeric", "float4" to "real", "float" to "float8",
    "character" to "char",
)

/** Types spelled in several words, by their first: the rest, and the one-word spelling they are. Longest first. */
private val PHRASES: Map<String, List<Pair<List<String>, String>>> = mapOf(
    "double" to listOf(listOf("precision") to "float8"),
    "character" to listOf(listOf("varying") to "varchar"),
    "char" to listOf(listOf("varying") to "varchar"),
    "bit" to listOf(listOf("varying") to "varbit"),
    "timestamp" to listOf(listOf("with", "time", "zone") to "timestamptz", listOf("without", "time", "zone") to "timestamp"),
    "time" to listOf(listOf("with", "time", "zone") to "timetz", listOf("without", "time", "zone") to "time"),
)

/** Words that change nothing where PostgreSQL allows them, and so are not tokens. */
private val OPTIONAL_WORDS = setOf("inner", "outer")
