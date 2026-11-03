package de.jplag.antlr

import de.jplag.Token
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTreeWalker
import java.io.File

/**
 * The one piece of the in-memory route to JPlag that has to be in JPlag's own package: [TokenCollector]
 * and [InternalListener], which bridge a parse tree to JPlag's tokens, are package-private. A split
 * package is legal on a plain classpath, which the Boot fat jar is. A class added rather than one of
 * JPlag's shadowed, so a JPlag upgrade that changes these members fails to compile here instead of
 * silently changing scores.
 *
 * [file] is an identifier, never opened.
 */
object InMemoryTokenCollectorAccess {

    fun collect(
        file: File,
        entryContext: ParserRuleContext,
        listener: AbstractAntlrListener,
        extractsSemantics: Boolean,
        walker: ParseTreeWalker,
    ): List<Token> {
        // As AbstractAntlrParserAdapter.parseFile does it, without opening the file: the file is set before
        // the walk and the end-of-file token added after it, or every comparison runs one token short.
        val collector = TokenCollector(extractsSemantics)
        collector.enterFile(file)
        walker.walk(InternalListener(listener, collector), entryContext)
        collector.addFileEndToken()
        return collector.getTokens()
    }
}
