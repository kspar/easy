package core.conf

import liquibase.integration.spring.SpringLiquibase
import org.springframework.beans.factory.annotation.Value
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

    /**
     * The changelog, and whether the test fixtures in it apply.
     *
     * `changesets/testdata.xml` seeds accounts, courses and exercises at fixed ids in the 9000s for
     * local development. It is part of the same changelog as the schema, so until it was given a
     * context it ran wherever the schema did — including against real data. Found on dev while
     * importing a production dump: production had never run those changesets, so rather than being
     * skipped they were applied, and `testdata-exercises` died on a duplicate `exercise_version` id
     * against rows the import had brought in. The same would have happened on the next production
     * deploy, where a failed migration means core does not start.
     *
     * Liquibase runs a changeset when it declares no context or when one of its contexts is active,
     * so a non-empty context that is not `testdata` runs the schema and skips the fixtures. Non-empty
     * matters: Liquibase reads "no contexts given" as "run everything", which is the behaviour being
     * prevented here.
     */
    @Bean
    fun liquibase(
        dataSource: DataSource,
        @Value("\${easy.core.db.test-data:false}") testData: Boolean,
    ): SpringLiquibase {
        return SpringLiquibase().apply {
            this.dataSource = dataSource
            changeLog = "classpath:/db/changelog.xml"
            setContexts(if (testData) "testdata" else "schema-only")
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
