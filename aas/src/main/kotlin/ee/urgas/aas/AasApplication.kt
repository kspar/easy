package ee.urgas.aas

import org.jetbrains.exposed.sql.Database
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import javax.annotation.PostConstruct
import javax.sql.DataSource

@SpringBootApplication
class AasApplication {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    fun dataSource(): DataSource {
        return DataSourceBuilder.create().build()
    }
}


@Component
class InitComponent(val dataSource: DataSource) {
    @PostConstruct
    fun init() {
        Database.connect(dataSource)
    }
}


fun main(args: Array<String>) {
    runApplication<AasApplication>(*args)
}

