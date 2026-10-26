package core.ems.service.code.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * JPlag's bounds (EZ-1667): never below what the tiling finds, or the optimised mode drops a pair from the
 * top without a trace - the copy a teacher was looking for - and tight enough to rule pairs out.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JPlagBoundsTest {

    /** A program of [statements] statements from [seed], names and constants varied. */
    private fun python(seed: Int, statements: Int): String {
        val random = Random(seed)
        val name = { listOf("total", "count", "x", "value", "acc", "n", "items", "data")[random.nextInt(8)] }
        return buildString {
            appendLine("def solve(data):")
            repeat(statements) { k ->
                when (random.nextInt(6)) {
                    0 -> appendLine("    ${name()} = ${name()} + ${random.nextInt(100)}")
                    1 -> appendLine("    for i in range(${random.nextInt(10)}, len(data)):\n        ${name()} += data[i]")
                    2 -> appendLine("    if ${name()} % ${k % 7 + 2} == 0:\n        print(${name()})")
                    3 -> appendLine("    ${name()} = [v * ${random.nextInt(9)} for v in data if v]")
                    4 -> appendLine("    while ${name()} > ${random.nextInt(50)}:\n        ${name()} -= 1")
                    else -> appendLine("    print(\"${name()}\", ${name()})")
                }
            }
            appendLine("    return total")
        }
    }

    private val java = listOf(
        "class A { int f(int[] a) { int s = 0; for (int i = 0; i < a.length; i++) { if (a[i] > 0) s += a[i]; } return s; } }",
        "class B { int g(int[] b) { int t = 0; for (int j = 0; j < b.length; j++) { if (b[j] > 0) t += b[j]; } return t; } }",
        "class C { String r(String s) { StringBuilder b = new StringBuilder(); for (int i = s.length() - 1; i >= 0; i--) b.append(s.charAt(i)); return b.toString(); } }",
        "class D { void main() { while (true) { if (Math.random() > 0.5) break; } System.out.println(\"done\"); } }",
    )

    /** Every pair's real numbers against its bound, for a session over [sources]. */
    private fun assertBounded(language: SimilarityLanguage, sources: List<SimilaritySource>, template: String? = null) {
        SimilarityWorkers(2, 64L shl 20).use { workers ->
            val run = RunContext(workers, Deadline.after(TimeUnit.SECONDS.toNanos(60)), 60)
            JPlagProvider(maxSolutionTokens = 10_000, maxRepetition = 1_000_000).open(ProviderRequest(language, sources, template), run).use { session ->
                val ids = session.prepare().usable
                session.ready(ids)
                var checked = 0
                for (i in ids.indices) for (j in i + 1 until ids.size) {
                    val bound = checkNotNull(session.bound(ids[i], ids[j]))
                    val real = session.score(ids[i], ids[j]) ?: continue
                    assertTrue(bound.rank >= real.rank, "${ids[i]}-${ids[j]}: ${real.rank} tokens matched over a bound of ${bound.rank}")
                    assertTrue(bound.score >= real.score - 1e-12, "${ids[i]}-${ids[j]}: score ${real.score} over a bound of ${bound.score}")
                    assertTrue(bound.similarity >= real.similarity - 1e-12, "${ids[i]}-${ids[j]}: similarity ${real.similarity} over ${bound.similarity}")
                    checked++
                }
                assertTrue(checked > 0, "no pair was checked")
            }
        }
    }

    @Test
    fun `no Python pair matches more than its bound - copies renamed, reordered, padded, and unrelated code`() {
        val originals = (1..12).map { python(it, 6 + it * 3) }
        val copies = listOf(
            originals[0].replace("total", "summa").replace("data", "andmed"),
            originals[1].lines().let { it.take(1) + it.drop(1).reversed() }.joinToString("\n"),
            originals[2] + "\n# kommentaar\nprint('lisa')\n",
            originals[3].substring(0, originals[3].length / 2),
        )
        val sources = (originals + copies).mapIndexed { i, text -> SimilaritySource(i + 1L, text, "s$i") }
        assertBounded(SimilarityLanguage.PYTHON, sources)
    }

    @Test
    fun `no Java pair matches more than its bound`() {
        assertBounded(SimilarityLanguage.JAVA, java.mapIndexed { i, text -> SimilaritySource(i + 1L, text, "s$i") })
    }

    @Test
    fun `with starter code marked, the bound is still not below the tiling, whose divisors the marking shrinks`() {
        // The starter code is excluded from matching and from each submission's divisor: the shares grow, so
        // a bound read from token counts alone would fall below them.
        val starter = "def solve(data):\n    total = 0\n    for i in range(0, len(data)):\n        total += data[i]\n    return total\n"
        val sources = (1..8).map { SimilaritySource(it.toLong(), starter + python(100 + it, 8 + it), "s$it") }
        assertBounded(SimilarityLanguage.PYTHON, sources, template = starter)
    }

    @Test
    fun `a window that crosses a token excluded from matching is no run, and the bound counts types it shares`() {
        // Types 1,2,3 then an excluded token then 1,2,3: with runs of three, each side has the run 1,2,3.
        val a = TokenRuns.of(intArrayOf(1, 2, 3, -1, 1, 2, 3), 3)
        val b = TokenRuns.of(intArrayOf(1, 2, 3), 3)
        assertEquals(3, a.matchedBound(b))
        // Runs of four would cross the excluded token: none, though the types are all shared.
        assertEquals(0, TokenRuns.of(intArrayOf(1, 2, 3, -1, 1, 2, 3), 4).matchedBound(TokenRuns.of(intArrayOf(1, 2, 3, 4), 4)))
        // The same types in another order share no run, and are bounded at none.
        assertEquals(0, TokenRuns.of(intArrayOf(1, 2, 3, 4), 2).matchedBound(TokenRuns.of(intArrayOf(4, 3, 2, 1), 2)))
        // A run repeated is covered every time, but no more often than the other has the types.
        assertEquals(2, TokenRuns.of(intArrayOf(1, 2, 1, 2, 1, 2), 2).matchedBound(TokenRuns.of(intArrayOf(1, 2, 9), 2)))
    }
}
