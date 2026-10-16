package de.jplag.antlr

import de.jplag.Token
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTreeWalker
import java.io.File

/**
 * The one piece of the in-memory route that cannot be written from outside JPlag's own package.
 *
 * JPlag's whole public input surface is `java.io.File` — [de.jplag.options.JPlagOptions] takes
 * `Set<File>`, and every read funnels through `de.jplag.util.FileUtils`, which uses
 * `new FileInputStream(file)` and `new FileReader(file, charset)`. Both are native and take only the
 * path string, so no `File` subclass, Jimfs-backed `toPath()`, or custom `FileSystemProvider` can
 * intercept them. Handing JPlag a submission therefore means handing the OS a real path — unless the
 * parse is done ourselves and the tokens fed straight in.
 *
 * Doing that needs three things that are package-private in `de.jplag.antlr`: [TokenCollector]'s
 * constructor, its [TokenCollector.enterFile] / [TokenCollector.addFileEndToken] /
 * [TokenCollector.getTokens], and the [InternalListener] class that bridges an
 * [AbstractAntlrListener] to a collector. None is *private*, so declaring this file in JPlag's
 * package is enough. That is a split package: legal on a plain classpath, which a Spring Boot fat
 * jar is, and illegal only under JPMS — which this application does not use.
 *
 * This is deliberately the smallest possible such class, and it deliberately *adds* a class rather
 * than shadowing one of JPlag's. The difference matters on upgrade: if JPlag changes these members,
 * this fails to compile. Shadowing `FileUtils` — the other way to reach the same goal — would keep
 * compiling and silently return wrong similarity scores.
 *
 * The `file` argument is an identifier, not a path. Nothing here opens it, and it need not exist;
 * it survives only as the label JPlag attaches to each token for match reporting.
 */
object InMemoryTokenCollectorAccess {

    /** [walker] is ANTLR's own walk or one that also bounds what it costs, as the caller decides. */
    fun collect(
        file: File,
        entryContext: ParserRuleContext,
        listener: AbstractAntlrListener,
        extractsSemantics: Boolean,
        walker: ParseTreeWalker,
    ): List<Token> {
        // Mirrors the body of AbstractAntlrParserAdapter.parseFile, minus the two lines that open
        // the file. Keep it in that order: the collector needs the file set before the walk, and the
        // end-of-file token after it, or comparisons silently run one token short per submission.
        val collector = TokenCollector(extractsSemantics)
        collector.enterFile(file)
        walker.walk(InternalListener(listener, collector), entryContext)
        collector.addFileEndToken()
        return collector.getTokens()
    }
}
