package de.jplag.java

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.LineMap
import com.sun.source.util.SourcePositions
import de.jplag.Token
import java.io.File

/**
 * The one piece of the in-memory Java route that cannot be written from outside JPlag's own package.
 *
 * JPlag's Java frontend is javac: it parses with the JDK's own compiler and walks the tree with
 * [TokenGeneratingTreeScanner], which is where the linguistically meaningful part lives - which Java
 * constructs become which tokens. Its only entry point, `JavacAdapter.parseFiles`, reads the files from
 * disk, so core does the parse itself (see `JavaFrontend`) and only the walk is done here. The walk
 * needs two things that are package-private in `de.jplag.java`: the scanner class, and [Parser.add],
 * the one method it hands each token to. Declaring this file in JPlag's package reaches both. It is the
 * same split package as `de.jplag.antlr.InMemoryTokenCollectorAccess`, legal on a plain classpath for
 * the same reasons, and it needs the same classloader for them - see the note on
 * `spring-boot-devtools` in CLAUDE.md.
 *
 * As small as it can be, and it adds classes rather than shadowing any of JPlag's: a JPlag release
 * that changes these members fails to compile here instead of quietly changing what is compared.
 *
 * The `file` argument is an identifier, not a path. Nothing here opens it, and it need not exist; it
 * survives only as the label JPlag attaches to each token.
 */
object InMemoryJavacAccess {

    /**
     * JPlag's tokens for one compilation unit javac has already parsed, ending in `FILE_END`.
     *
     * Mirrors the loop body of `JavacAdapter.parseFiles`, minus the line that makes a `File` of the
     * unit's URI; keep the order, since the file-end token must come after the walk. [lineMap] is the
     * unit's own and [positions] the parse's `SourcePositions` as JPlag's adapter wraps them,
     * `FixedSourcePositions` - both taken as parameters so the caller can guard what asking them costs,
     * and what they throw on.
     */
    fun tokens(file: File, unit: CompilationUnitTree, lineMap: LineMap, positions: SourcePositions): List<Token> {
        val collector = Collector()
        unit.accept(TokenGeneratingTreeScanner(file, collector, lineMap, positions, unit), null)
        collector.tokens += Token.semanticFileEnd(file)
        return collector.tokens
    }

    /**
     * A [Parser] that only collects. `Parser` keeps its list in a private field it creates only when
     * it reads files itself, so the scanner's calls to [add] would find no list; overriding [add],
     * which is package-private and so overridable only from inside this package, is the way in.
     * (Kotlin, having no package-private of its own, compiles the override as `protected` - which is
     * why the file-end token above goes straight into the list rather than through it.)
     */
    private class Collector : Parser() {
        val tokens = ArrayList<Token>()

        override fun add(token: Token) {
            tokens += token
        }
    }
}
