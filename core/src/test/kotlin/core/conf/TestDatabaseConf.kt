package core.conf

import jakarta.annotation.PostConstruct
import liquibase.Liquibase
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.FileSystemResourceAccessor
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource


/**
 * SpringBootTest(classes = [EasyCoreApp::class]) loads all classes such as Moodle sync cron job, which require database
 * schema. Doing Liquibase update in BeforeEach/BeforeAll is too late for initial setup.
 *
 * Init db with Liquibase schema.
 */
@Configuration
class InitTestDatabase(val dataSource: DataSource) {
    @Value("\${easy.core.liquibase.changelog}")
    private lateinit var changelogFile: String

    @PostConstruct
    fun init() {
        Database.connect(dataSource)
        TransactionManager.manager.defaultMaxAttempts = 6

        dropAndUpdateSchema(changelogFile, JdbcConnection(dataSource.connection))
    }
}

fun dropAll(changelogFile: String, connection: JdbcConnection) {
    Liquibase(changelogFile, FileSystemResourceAccessor(), connection).dropAll()
}

fun dropAndUpdateSchema(changelogFile: String, connection: JdbcConnection) {
    val lb = Liquibase(changelogFile, FileSystemResourceAccessor(), connection)
    lb.dropAll()
    lb.update("")
}
