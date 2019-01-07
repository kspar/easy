package ee.urgas.aas

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AasApplication

fun main(args: Array<String>) {
    runApplication<AasApplication>(*args)
}
