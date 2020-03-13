package core.ems.service.code

import mu.KotlinLogging
import java.util.*


private val log = KotlinLogging.logger {}

fun mainCommented() {
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
    val strategy = DiceCoefficientStrategy() // TODO: good
    log.info { strategy.findClosestN(code1, listOf(code1, code2, code3), 3) }
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

    private fun score(first: String, second: String): Double {
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

