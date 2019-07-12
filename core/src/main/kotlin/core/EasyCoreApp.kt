package core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@EnableAsync
@SpringBootApplication
class EasyCoreApp

fun main(args: Array<String>) {
    runApplication<EasyCoreApp>(*args)
}
