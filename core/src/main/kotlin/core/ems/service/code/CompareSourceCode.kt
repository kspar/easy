package core.ems.service.code

import me.xdrop.fuzzywuzzy.FuzzySearch
import mu.KotlinLogging
import java.util.*


private val log = KotlinLogging.logger {}

fun mainTest() {
    val code1 = """
    s = 3
    for i in range(6):
        print(i)
        
    def f(x):
        if x > 0:
            return f(x - 1)
        else:
            return 1
            
    print(f(s))
    """

    val code2 = """
    
    for kkkkk in range(6):
        print(kkkkk)

    def fsome(uus_muutuja):
        if uus_muutuja > 0:
            return f(uus_muutuja - 1)
        else:
            return 1
            
    print(fsome(i))
    """

    val code3 = """
    import math
    
    glob = None

    def hello():
        if not glob:
            return "No!
        else:
            return "Hello world!"
            
    print(hello)
    """


    val code4 = """
    midagi = input()
    muud = True and midagi or False
    if midagi:
        print(1)
    print(2)
    """

    val code5 = """
    midagi = input()
    muud = midagi or True and True or True and False
    if midagi and muud or True:
        print(2)
    print(1)
    """


    val code6 = """
    print(1)
    print(1)
    print(1)
    print(1)
    print(1)
    print(1)
    """

    val code7 = """
    print(1)
    """

    val strategy = DiceCoefficientStrategy() // TODO: good
    //log.info { strategy.findClosestN(code1, listOf(code1, code2, code3), 3) }


    val old = strategy.score(code1, code2)
    val old2 = strategy.score(code1, code3)
    val old3 = strategy.score(code4, code5)
    val old4 = strategy.score(code6, code7)

    val new = fuzzy(code1, code2)
    val new2 = fuzzy(code1, code3)
    val new3 = fuzzy(code4, code5)
    val new4 = fuzzy(code6, code7)

    log.info { "Similar $new vs $old" }
    log.info { "Different $new2 vs $old2" }
    log.info { "Different semantics $new3 vs $old3" }
    log.info { "Print comparison: $new4 vs $old4" }
}

fun fuzzy(s1: String, s2: String): Int {
    return FuzzySearch.ratio(s1, s2)
}

/**
 * Rewrote from: https://github.com/rrice/java-string-similarity
 * https://en.wikipedia.org/wiki/S%c3%b8rensen%e2%80%93Dice_coefficient
 */
class DiceCoefficientStrategy {

    fun findClosestN(sourceCode: String, targetSourceCode: List<String>, n: Int): List<Pair<Int, Double>> {
        return targetSourceCode.mapIndexed { i, it -> i to score(sourceCode, it) }
                .sortedByDescending { it.second }
                .take(n)
    }

    fun score(first: String, second: String): Double {
        val s1 = splitIntoBigrams(first)
        val s2 = splitIntoBigrams(second)

        s1.retainAll(s2)

        val nt = s1.size
        return 2.0 * nt / (s1.size + s2.size)
    }

    private fun splitIntoBigrams(s: String): TreeSet<String> {
        return when {
            s.length < 2 -> TreeSet<String>(listOf(s))
            else -> TreeSet<String>(s.windowed(2))
        }
    }
}

