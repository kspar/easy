package core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import java.util.TimeZone

@EnableAsync
@SpringBootApplication
class EasyCoreApp

fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    runApplication<EasyCoreApp>(*args)
}
