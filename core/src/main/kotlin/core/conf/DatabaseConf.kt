package core.conf

import liquibase.integration.spring.SpringLiquibase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import javax.annotation.PostConstruct
import javax.sql.DataSource

@Configuration
class DataSourceConf {
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    fun dataSource(): DataSource {
        return DataSourceBuilder.create().build()
    }

    @Bean
    fun liquibase(dataSource: DataSource): SpringLiquibase {
        return SpringLiquibase().apply {
            this.dataSource = dataSource
            changeLog = "classpath:/db/changelog.xml"
        }
    }
}

@Configuration
@DependsOn("liquibase")
class DatabaseInit(val dataSource: DataSource) {
    @PostConstruct
    fun init() {
        Database.connect(dataSource)
        TransactionManager.manager.defaultRepetitionAttempts = 6
    }
}
