package core.ems.service.code.similarity

import de.jplag.Token
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.concurrent.FutureTask

/**
 * The Java frontend: JPlag's own Java tokens, from a parse core does itself, in memory.
 *
 * The first test is the one the frontend stands on. Everything that decides what a Java token is lives
 * in JPlag's tree scanner, and core only changes how the tree gets to it - so what JPlag's own Java
 * module made of the same sources, from files as it insists, is recorded, and the two token streams must
 * be the same. A javac or JPlag upgrade that changes what the in-memory route sees then fails here.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JavaFrontendTest {

    private fun signature(tokens: List<Token>) = tokens.map {
        "${it.type.description}@${it.startLine}:${it.startColumn}-${it.endLine}:${it.endColumn}"
    }

    private fun tokens(source: String, maxTokens: Int = Int.MAX_VALUE) =
        SimilarityLanguage.JAVA.tokenise(File("Main"), source, maxTokens)

    /** Programs that between them reach most of the scanner: classes, generics, lambdas, records, switches. */
    private val programs = listOf(
        """
            import java.util.*;

            public class Main {
                public static void main(String[] args) {
                    Scanner sc = new Scanner(System.in);
                    int n = sc.nextInt();
                    int[] a = new int[n];
                    for (int i = 0; i < n; i++) a[i] = sc.nextInt();
                    long sum = 0;
                    for (int v : a) { if (v > 0) sum += v; else continue; }
                    System.out.println(sum);
                }
            }
        """,
        """
            package ee.ut.lahendus;

            import java.util.List;
            import java.util.function.Function;

            public record Pair<A, B>(A first, B second) {
                public <C> Pair<A, C> map(Function<? super B, ? extends C> f) {
                    return new Pair<>(first, f.apply(second));
                }

                static int describe(Object o) {
                    return switch (o) {
                        case Integer i when i > 0 -> 1;
                        case String s -> s.length();
                        default -> 0;
                    };
                }
            }
        """,
        """
            import java.io.*;

            class Konto implements Comparable<Konto> {
                private double saldo;
                enum Liik { ARVELDUS, HOIUS }

                synchronized void lisa(double s) {
                    assert s > 0 : "positiivne";
                    saldo += s;
                }

                public int compareTo(Konto o) { return Double.compare(saldo, o.saldo); }

                String loe(File f) throws IOException {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        return r.readLine();
                    } catch (FileNotFoundException e) {
                        throw new UncheckedIOException(e);
                    } finally {
                        saldo = 0;
                    }
                }

                Runnable r = () -> { do { saldo--; } while (saldo > 0); };
                String tekst = ""${'"'}
                    mitu
                    rida""${'"'};
                int[][] maatriks = {{1, 2}, {3, 4}};
                Object anon = new Object() { @Override public String toString() { return "x"; } };
            }
        """,
    ).map { it.trimIndent() }

    /**
     * JPlag's own Java module, run on each program from a file, recorded in `similarity/java-tokens/`.
     * Recorded rather than run here because JPlag reads a file through ICU's charset detection, and a
     * library on the test classpath only for that is one more dependency to keep. A javac or JPlag
     * upgrade that changes what the in-memory route sees still fails here; whether JPlag's own tokens
     * changed with it is then checked by re-recording - run `JavaLanguage().parse(setOf(file), false)`
     * on each program with `com.ibm.icu:icu4j-charset` on the classpath once, and diff the files.
     */
    @Test
    fun `the in-memory parse gives exactly JPlag's own Java tokens`() {
        programs.forEachIndexed { index, program ->
            val recorded = javaClass.getResource("/similarity/java-tokens/program$index.txt")!!.readText().lines().filter { it.isNotEmpty() }
            assertEquals(recorded, signature(tokens(program)), "program $index tokenised differently")
        }
    }

    @Test
    fun `a syntax error is recovered from, not refused`() {
        // JPlag's own Java module refuses any file javac reports an error in. Here, as for Python, a
        // broken line must not be a way out of the comparison: javac recovers, and the rest is tokenised.
        val broken = programs[0].replace("int n = sc.nextInt();", "int n = sc.nextInt()")
        val intact = tokens(programs[0])
        val recovered = tokens(broken)
        assertTrue(recovered.size >= intact.size - 2, "recovered only ${recovered.size} of ${intact.size} tokens")
    }

    @Test
    fun `lexical tokens are counted as javac would split them, comments and literals included`() {
        val source = """
            // a comment with "quotes" and /* markers */
            int x = 1; /* block // with a line comment inside */
            String s = "a // not a comment", t = ""${'"'}
                a text block with "quotes" and // no comment
                ""${'"'};
            char c = '\'', d = '"';
            x >>>= 2; List<List<String>> l = null;
        """.trimIndent()
        // int x = 1 ; | String s = "…" , t = """…""" ; | char c = '\'' , d = '"' ; | x >>>= 2 ; | List < List < String >> l = null ;
        assertEquals(5 + 9 + 9 + 4 + 10, lexicalTokens(source, Int.MAX_VALUE))
    }

    @Test
    fun `comments do not count toward the token cap, and code does`() {
        val code = "class A { int f() { return 1; } }"
        val padded = code + "\n// " + "padding ".repeat(10_000) + "\n/* " + "more ".repeat(10_000) + "*/"
        assertEquals(signature(tokens(code, maxTokens = 20)), signature(tokens(padded, maxTokens = 20)))
        assertThrows<SolutionTooLarge> { tokens("class A { " + "int f() { return 1; } ".repeat(10) + "}", maxTokens = 20) }
    }

    @Test
    fun `the cap counts what javac reads, through unicode escapes and every kind of line end`() {
        // javac translates unicode escapes before anything else, ends a line at a lone CR as well as an
        // LF, reads one character into a char literal and opens a text block only at a line end. A count
        // that did none of that took real code for a comment or a literal: behind one escaped newline in
        // a `//` comment, 10,000 chained comparisons counted as nothing and were parsed - quadratically,
        // in a parse no clock can stop - while the plain spelling is refused at once.
        val esc = "\\u" // assembled, so this file holds no escape of its own
        val chain = "boolean f(int a) { return a" + " < a".repeat(10_000) + "; }"
        val hiding = mapOf(
            "an escaped LF ends a line comment" to "class A { // ${esc}000a $chain }",
            "an escaped CR ends a line comment" to "class A { // ${esc}000d $chain }",
            "a lone CR ends a line comment" to "class A { //\r$chain }",
            "escaped quotes are quotes" to "class A { String s = \"${esc}0022; $chain String t = ${esc}0022\"; }",
            "an escaped star-slash ends a block comment" to "class A { /* ${esc}002a/ $chain */ }",
            "a char literal holds one character" to "class A { char c = 'x; $chain char d = 'y'; }",
            "a text block opens only at a line end" to "class A { String s = \"\"\" $chain \"\"\"; }",
            "an escaped backslash spelled in fullwidth digits is one" to
                "class A { String s = \"$esc\uFF10\uFF105c\\${esc}0022; $chain }",
            "a number ends at its second dot" to "class A { Object x = 1.0" + ".a".repeat(5_001) + "; }",
        )
        for ((why, source) in hiding) {
            assertThrows<SolutionTooLarge>(why) { onDeepStack { tokens(source, maxTokens = 10_000) } }
        }
        // And an escape counts as the one character it stands for.
        assertEquals(lexicalTokens(programs[0], Int.MAX_VALUE), lexicalTokens(programs[0].replace(";", "${esc}003b"), Int.MAX_VALUE))
    }

    @Test
    fun `member selects JPlag would print quadratically are refused before its walk, whatever the count`() {
        // JPlag prints every select's expression and asks no position meanwhile, so neither the cap nor
        // the clock saw it: 24,000 selects tokenised for 48 s. Measured on the tree instead, they cost
        // nothing; a long but ordinary chain is still compared.
        val chain = { n: Int -> "class A { Object x = b" + ".a".repeat(n) + "; }" }
        onDeepStack { tokens(chain(2_000), maxTokens = 1_000_000) }
        val started = System.nanoTime()
        assertThrows<SolutionTooComplex> { onDeepStack { tokens(chain(24_000), maxTokens = 1_000_000) } }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "the walk ran before the refusal")
    }

    @Test
    fun `an enum whose constants have bodies is tokenised, not dropped`() {
        // JPlag's scanner takes a variable's end from its initializer's printed form rather than from the
        // source, which near the start of a file comes out negative, and javac's line map throws on it.
        // This compiles, and was dropped as CANNOT_PARSE; JPlag's own Java module fails the same way.
        val source = "enum Op { PLUS { int a(int x,int y){return x+y;} }, TIMES { int a(int x,int y){return x*y;} }; abstract int a(int x,int y); }"
        assertTrue(tokens(source).size > 10)
    }

    @Test
    fun `a walk javac makes quadratic is stopped by the clock, as TOO_COMPLEX`() {
        // JPlag's scanner asks javac where each node starts and ends, and javac walks the subtree to
        // answer: 20,000 nested ifs took 30 s. The token cap is lifted here so that only the clock is
        // left to stop it.
        val nested = "class A { void f() { " + "if (a) ".repeat(20_000) + "g(); } }"
        val started = System.nanoTime()
        assertThrows<SolutionTooComplex> { onDeepStack { tokens(nested) } }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 10_000, "the walk was allowed to run for ${tookMs}ms")
    }

    @Test
    fun `an overflow inside javac reaches the caller as the StackOverflowError it is`() {
        // javac catches what its parser throws, Errors included, and reports it as an
        // IllegalStateException around it. Filed as that, it would be an ordinary parse failure - and
        // so would an OutOfMemoryError, which must reach the error handler instead. So the Error is
        // unwrapped; this reaches it on a stack small enough for javac to overflow on.
        val deep = "class A { int x = " + "(".repeat(20_000) + "1" + ")".repeat(20_000) + "; }"
        val task = FutureTask { runCatching { tokens(deep) }.exceptionOrNull() }
        Thread(null, task, "small-stack", 256L * 1024).apply { start() }.join()
        val thrown = task.get()
        assertTrue(thrown is StackOverflowError, "javac's overflow surfaced as $thrown")
    }

    @Test
    fun `javac is asked for nothing a JRE does not ship`() {
        // Production runs on Ubuntu's openjdk-25-jre-headless, which has javac but no lib/ct.sym, and
        // `--release` needs ct.sym: JPlag's own adapter passes it, and there it failed on every
        // submission - measured - while passing on every JDK, which is all a developer or CI runs. So no
        // test run here can see that failure; this pins the one thing that caused it.
        assertTrue(JavaFrontend.options.none { it.startsWith("--release") }, "${JavaFrontend.options}")
        assertTrue(JavaFrontend.available, "javac is missing from the JVM running the tests")
    }

    private fun <T> onDeepStack(block: () -> T): T {
        val task = FutureTask(block)
        Thread(null, task, "deep-stack", 64L shl 20).apply { start() }.join()
        return try {
            task.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }
}
