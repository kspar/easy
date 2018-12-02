package ee.urgas.ems

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EmsApplication

fun main(args: Array<String>) {
    runApplication<EmsApplication>(*args)
}

