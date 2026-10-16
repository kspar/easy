package core.ems.service.code.similarity

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.LineMap
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.Tree
import com.sun.source.util.JavacTask
import com.sun.source.util.SourcePositions
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees
import de.jplag.java.FixedSourcePositions
import de.jplag.java.InMemoryJavacAccess
import java.io.File
import java.io.Writer
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

/**
 * Java, through JPlag's own Java module - which is javac: the JDK's compiler parses, and JPlag's tree
 * scanner decides which constructs become which tokens (see [InMemoryJavacAccess]).
 *
 * The parse is core's own rather than JPlag's for two reasons. JPlag's adapter reads the files from
 * disk. And it asks javac for `--release=<this JDK>`, which needs the JDK's `lib/ct.sym` - a file a
 * full JDK ships and a JRE does not. Production runs on Ubuntu's `openjdk-25-jre-headless`, which does
 * include javac itself (the `jdk.compiler` module) but not ct.sym, so there JPlag's own parse failed on
 * every submission, measured, while it passed on every JDK a developer or CI runs. Nothing here needs
 * ct.sym: a parse reads no class files, so only `-proc:none` is passed, as JPlag also passes it - it
 * keeps javac from looking for annotation processors on the application's classpath.
 *
 * Syntax errors are recovered from, not refused: javac's parser recovers and returns a tree, and it is
 * compared. That is the Python frontend's rule for the same reason - a submission cannot opt out of
 * detection by containing one broken line - where JPlag's own Java module refuses any file with an
 * error.
 *
 * Cost: javac's setup is most of it, about 12 ms per solution whatever its size, so a cohort of 500
 * takes some 1.5 s on four threads. Parsing many solutions in one javac task measured 0.2-0.4 ms each;
 * not done, because one task is one parse that nothing can interrupt, so a batch would make one
 * pathological solution everybody's problem.
 */
internal object JavaFrontend : SimilarityFrontend {

    /**
     * 8, one below JPlag's own Java default of 9, calibrated like Python's on a synthetic cohort (the
     * sweep is in doc/jplag-integration.md). The case that decides it is a class of small methods copied
     * in another order: each method is a run of a few tokens, so above 8 the runs stop matching - the
     * renamed copy scored 81% at 8 and 59% at 9, and at 10 the edited copy (35%) was no longer told from
     * an honest pair (32%). Below 8 the copies scored higher, but honest solutions of the same exercise
     * began to share runs as long, so the margin did not grow.
     */
    override val minimumTokenMatch = 8

    /** Null in a runtime without the `jdk.compiler` module, which some minimal JREs leave out. */
    private val javac: JavaCompiler? by lazy { ToolProvider.getSystemJavaCompiler() }

    override val available: Boolean get() = javac != null

    /** What javac is asked for: nothing a JRE lacks - see the class notes on `--release`. */
    internal val options = listOf("-proc:none")

    override fun read(id: File, source: String, maxTokens: Int): Tokenised {
        // Counted before javac sees anything, because javac is where the cost is. Its parse is linear on
        // most input and quadratic on some - 50,000 chained `<` took 21 s - and nothing can stop it once
        // started, so the count is what bounds it.
        if (lexicalTokens(source, maxTokens) > maxTokens) throw SolutionTooLarge(maxTokens)
        val compiler = checkNotNull(javac) { "javac is not available in this JVM" }
        val file = object : SimpleJavaFileObject(URI.create("string:///${id.name}.java"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
        }
        // Diagnostics are the recovered syntax errors this deliberately compares anyway; they are only
        // counted. A listener rather than none, too: with none, javac prints each diagnostic to the
        // writer, and with one it keeps the end positions JPlag's scanner asks for.
        val errorLines = HashSet<Int>()
        val listener = DiagnosticListener<JavaFileObject> { if (it.kind == Diagnostic.Kind.ERROR) errorLines += it.lineNumber.toInt() }
        val task = compiler.getTask(Writer.nullWriter(), null, listener, options, null, listOf(file)) as JavacTask
        val unit: CompilationUnitTree = task.parse().singleOrNull()
            ?: throw IllegalStateException("javac parsed no compilation unit")
        val printing = selectPrinting(unit)
        if (printing > MAX_SELECT_PRINTING) {
            throw SolutionTooComplex("Member selects would print $printing tree nodes, above $MAX_SELECT_PRINTING")
        }
        val positions = TimedSourcePositions(
            FixedSourcePositions(Trees.instance(task).sourcePositions),
            TimeUnit.MILLISECONDS.toNanos(PARSE_TIME_LIMIT_MS),
        )
        return Tokenised(InMemoryJavacAccess.tokens(id, unit, ClampedLineMap(unit.lineMap), positions), recovered = mostlyRecovered(errorLines, source))
    }
}

/**
 * How many tree nodes JPlag's walk would print for [unit]'s member selects: the size of each select's
 * expression, summed.
 *
 * JPlag's `visitMemberSelect` prints every select's expression with `toString()` - to see whether it is
 * `this` - and asks no source position while it does, so neither the token cap nor the clock on
 * positions bounds it. A chain of n selects prints n²/2 nodes: 24,000 of them, behind a number the
 * counter misread as one token, tokenised for 48 seconds holding the permit. This pass is linear and
 * runs before the walk, so the walk's cost is bounded whatever the counter gets wrong.
 */
internal fun selectPrinting(unit: CompilationUnitTree): Long {
    var printed = 0L
    val sizes = object : TreeScanner<Long, Unit?>() {
        override fun scan(tree: Tree?, p: Unit?): Long = if (tree == null) 0 else 1 + (super.scan(tree, p) ?: 0)
        override fun reduce(r1: Long?, r2: Long?): Long = (r1 ?: 0) + (r2 ?: 0)
        override fun visitMemberSelect(node: MemberSelectTree, p: Unit?): Long =
            (super.visitMemberSelect(node, p) ?: 0).also { printed += it }
    }
    sizes.scan(unit, null)
    return printed
}

/**
 * Member-select printing a solution may cost, in tree nodes; see [selectPrinting]. About a third of a
 * second of JPlag's walk, and some thousand times what real code needs: a chain of 2,000 selects is
 * still compared.
 */
internal const val MAX_SELECT_PRINTING = 4_000_000L

/**
 * javac's line map, answering for a position before the start of the file as for its first character.
 *
 * JPlag's scanner takes a variable's end from its initializer's printed form rather than from the source
 * (`visitVariable`: the end, less one, less `initializer.toString().length()`), which near the start of a
 * file comes out negative - for an enum whose constants have bodies, say - and javac's map throws on a
 * negative position. So a solution that compiled was dropped as CANNOT_PARSE, as it is by JPlag's own
 * Java module. Clamped, only that one token's span moves, and it was wrong already.
 */
private class ClampedLineMap(private val base: LineMap) : LineMap {
    override fun getStartPosition(line: Long): Long = base.getStartPosition(line)
    override fun getPosition(line: Long, column: Long): Long = base.getPosition(line, column)
    override fun getLineNumber(pos: Long): Long = base.getLineNumber(maxOf(pos, 0))
    override fun getColumnNumber(pos: Long): Long = base.getColumnNumber(maxOf(pos, 0))
}

/**
 * javac's source positions, with a wall-clock limit on how long one walk may spend asking for them.
 *
 * JPlag's scanner asks for the start and end of every node it makes a token of, and javac computes most
 * of those by walking the node's subtree: the start of `a.b().c()` is the start of `a.b()`, which is
 * the start of `a`. So a chain or a nesting n deep costs n², measured: 2,500 chained calls took 1.8 s
 * and 5,000 took 5 s; 50,000 took ten minutes, and 20,000 nested `if`s 30 s. The token cap bounds n to
 * where the worst of these is under two seconds; this bounds the time of anything it does not, at
 * [PARSE_TIME_LIMIT_MS] like Python's parse. Each question is at most one walk of one subtree, so
 * reading the clock between questions is fine-grained enough.
 */
private class TimedSourcePositions(private val base: SourcePositions, private val limitNanos: Long) : SourcePositions {
    private val deadline = Deadline.after(limitNanos)
    private var asked = 0

    override fun getStartPosition(file: CompilationUnitTree, tree: Tree): Long {
        check()
        return base.getStartPosition(file, tree)
    }

    override fun getEndPosition(file: CompilationUnitTree, tree: Tree): Long {
        check()
        return base.getEndPosition(file, tree)
    }

    private fun check() {
        if ((++asked and CLOCK_EVERY) == 0 && deadline.passed()) {
            throw SolutionTooComplex("Tokenising took more than ${limitNanos / 1_000_000} ms")
        }
    }

    private companion object {
        /** Questions between two reads of the clock, less one: a mask, so the check is a single AND. */
        const val CLOCK_EVERY = 15
    }
}

/**
 * How many lexical tokens [source] has, counting no further than just past [limit]: names, keywords,
 * literals, operators and separators, without comments or whitespace - the unit every frontend's cap
 * counts in.
 *
 * javac has no public lexer, so this is a scanner of its own, and wherever a difference from javac's
 * reading could move a token boundary a long way it reads the source javac's way - because there it can
 * be told that real code is a comment or a literal, and this count is all that bounds a parse nothing can
 * stop. So unicode escapes are translated first (JLS 3.3), a line ends at LF, CR or CRLF, a char literal
 * holds one character, a string ends at its line's end, and a text block opens only where its `"""` ends
 * a line. Read naively, each of those let code past the cap: behind one escaped newline in a `//`
 * comment, 10,000 chained comparisons counted as nothing. Within code it errs by a token or so on odd
 * input (a malformed number), which a cap of thousands cannot see.
 *
 * The one escape it does not follow is a unicode-escaped backslash, `\u005c`, whose effect on the backslashes
 * around it javac settles by rules subtle enough to get wrong. A source that has one is counted with its
 * comments and literals as code: more than javac would see, never less.
 */
internal fun lexicalTokens(source: String, limit: Int): Int =
    if (hasEscapedBackslash(source)) countLexical(source, limit, opaque = true)
    else countLexical(unicodeEscapesTranslated(source), limit, opaque = false)

/**
 * Whether [source] has a unicode escape that javac reads as a backslash. Decoded with the same digit
 * rule javac uses (any `Character.digit`, fullwidth included) rather than matched as ASCII text: an ASCII
 * pattern let `\u００5c` past, the translator below then paired the backslashes after it differently
 * from javac, and the rest of that line counted as string content while javac read it as code.
 */
private fun hasEscapedBackslash(source: String): Boolean {
    var from = 0
    while (true) {
        val at = source.indexOf("\\u", from)
        if (at < 0) return false
        var hex = at + 1
        while (hex < source.length && source[hex] == 'u') hex++
        if (hex + 4 <= source.length) {
            val digits = (hex until hex + 4).map { source[it].digitToIntOrNull(16) ?: -1 }
            if (digits.all { it >= 0 } && digits.fold(0) { value, digit -> value * 16 + digit } == '\\'.code) return true
        }
        from = at + 1
    }
}

/** [source] with its unicode escapes translated, as javac reads it before anything else (JLS 3.3). */
private fun unicodeEscapesTranslated(source: String): String {
    if (!source.contains("\\u")) return source
    val out = StringBuilder(source.length)
    var i = 0
    // A backslash begins an escape only after an even number of others: `\\u0041` is not one.
    var backslashes = 0
    while (i < source.length) {
        val c = source[i]
        if (c == '\\' && backslashes % 2 == 0 && source.getOrNull(i + 1) == 'u') {
            var hex = i + 1
            while (hex < source.length && source[hex] == 'u') hex++
            if (hex + 4 <= source.length && (hex until hex + 4).all { source[it].digitToIntOrNull(16) != null }) {
                out.append(source.substring(hex, hex + 4).toInt(16).toChar())
                i = hex + 4
                backslashes = 0
                continue
            }
        }
        backslashes = if (c == '\\') backslashes + 1 else 0
        out.append(c)
        i++
    }
    return out.toString()
}

/** [lexicalTokens] over text already translated; [opaque], comments and literals are counted as code. */
private fun countLexical(source: String, limit: Int, opaque: Boolean): Int {
    var i = 0
    var count = 0
    val n = source.length
    while (i < n && count <= limit) {
        val c = source[i]
        when {
            c.isWhitespace() -> {
                i++
                continue
            }
            !opaque && source.startsWith("//", i) -> {
                i = lineEnd(source, i)
                continue
            }
            !opaque && source.startsWith("/*", i) -> {
                i = source.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                continue
            }
            !opaque && source.startsWith("\"\"\"", i) -> i = textBlockEnd(source, i)
            !opaque && c == '"' -> i = stringEnd(source, i + 1)
            !opaque && c == '\'' -> i = charLiteralEnd(source, i + 1)
            Character.isJavaIdentifierStart(c) -> {
                i++
                while (i < n && Character.isJavaIdentifierPart(source[i])) i++
            }
            c.isDigit() || (c == '.' && i + 1 < n && source[i + 1].isDigit()) -> {
                // One '.' at most, as in any Java number. Taking every '.' made `1.0.a.a…` a single
                // token, while javac reads a chain of member selects that JPlag walks in quadratic
                // time no clock covers: 48 KB of it counted 9 and tokenised for 48 seconds.
                var dotSeen = c == '.'
                i++
                while (i < n) {
                    val d = source[i]
                    val signOfExponent = (d == '+' || d == '-') && source[i - 1] in "eEpP" && !source.startsWith("0x", i - 2)
                    if (d == '.') {
                        if (dotSeen) break
                        dotSeen = true
                        i++
                    } else if (d.isLetterOrDigit() || d == '_' || signOfExponent) i++ else break
                }
            }
            else -> i += JAVA_OPERATORS.firstOrNull { source.startsWith(it, i) }?.length ?: 1
        }
        count++
    }
    return count
}

/** Where the line containing [from] ends: at an LF or a CR, both line terminators to javac. */
private fun lineEnd(source: String, from: Int): Int {
    var i = from
    while (i < source.length && source[i] != '\n' && source[i] != '\r') i++
    return i
}

/** Past a string literal whose body starts at [from]. An unterminated one ends at its line, as javac's does. */
private fun stringEnd(source: String, from: Int): Int {
    var i = from
    while (i < source.length) {
        when (source[i]) {
            '\\' -> if (i + 1 < source.length && source[i + 1] != '\n' && source[i + 1] != '\r') i += 2 else return i + 1
            '"' -> return i + 1
            '\n', '\r' -> return i
            else -> i++
        }
    }
    return source.length
}

/**
 * Past a char literal whose body starts at [from]: one character or one escape, then the closing quote if
 * it is there. javac reads no further, closed or not, and lexes whatever follows as code.
 */
private fun charLiteralEnd(source: String, from: Int): Int {
    var i = from
    if (i >= source.length || source[i] == '\n' || source[i] == '\r') return i
    if (source[i] == '\'') return i + 1
    if (source[i] == '\\') {
        i++
        if (i < source.length && source[i] in '0'..'7') {
            val digits = if (source[i] <= '3') 3 else 2
            var read = 0
            while (read < digits && i < source.length && source[i] in '0'..'7') {
                i++
                read++
            }
        } else if (i < source.length && source[i] != '\n' && source[i] != '\r') {
            i++
        }
    } else {
        i++
    }
    return if (i < source.length && source[i] == '\'') i + 1 else i
}

/**
 * Past a text block whose `"""` is at [at]; or, where javac would not open one - its opening delimiter
 * must end its line - past that `"""` alone, an error to javac, which lexes what follows as code.
 */
private fun textBlockEnd(source: String, at: Int): Int {
    var i = at + 3
    while (i < source.length && (source[i] == ' ' || source[i] == '\t' || source[i] == '\u000C')) i++
    if (i >= source.length || (source[i] != '\n' && source[i] != '\r')) return i
    while (i < source.length) {
        when {
            source[i] == '\\' -> i += 2
            source.startsWith("\"\"\"", i) -> return i + 3
            else -> i++
        }
    }
    return source.length
}

/** Java's operators and separators of more than one character, longest first so the longest matches. */
private val JAVA_OPERATORS = listOf(
    ">>>=", "<<=", ">>=", ">>>", "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=",
    "+=", "-=", "*=", "/=", "&=", "|=", "^=", "%=", "<<", ">>",
)
