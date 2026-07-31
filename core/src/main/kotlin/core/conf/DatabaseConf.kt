package core.conf

import liquibase.integration.spring.SpringLiquibase
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import jakarta.annotation.PostConstruct
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
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
        TransactionManager.manager.defaultMaxAttempts = 6
    }
}
