package core.testing

import core.EasyCoreApp
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
// Boot 4 split spring-boot-test-autoconfigure into per-technology modules, so this is no longer
// org.springframework.boot.test.autoconfigure.web.servlet — see doc/java-25-migration.md.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ContextConfiguration

/**
 * A test that runs against the real application: real Spring context, real filter chain, real
 * PostgreSQL.
 *
 * Use this verbatim. **Do not add `@TestPropertySource`, `@MockitoBean`, `@ActiveProfiles` or any
 * other context-affecting annotation to a test class.** Spring caches one context per distinct
 * configuration, so each of those forks a second one and pays another ~10 seconds of startup — and
 * the cost is per *variant*, so it compounds quietly as tests are added. One context for the whole
 * suite is the single rule that keeps this suite fast enough to gate a deploy.
 *
 * If a test genuinely needs a different configuration, that is a design discussion rather than a
 * local annotation. There is currently one legitimate exception, JwtResourceServerTest, which has
 * to point `jwk-set-uri` at a JWKS server it starts itself; it says so in its own docblock.
 *
 * What this bundles:
 *
 * - `@SpringBootTest` with the default MOCK web environment. `@AutoConfigureMockMvc` then gives a
 *   MockMvc that runs the whole `SecurityFilterChain`, `DispatcherServlet`, Jackson and
 *   `EasyExceptionHandler` — everything a real request meets except the connector.
 * - [TestDatabaseInitializer], pointing the datasource at the Testcontainers PostgreSQL.
 * - [DatabaseResetExtension], emptying every table before each test.
 *
 * Liquibase migrates the schema once, at context startup, through the same `SpringLiquibase` bean a
 * deploy uses.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(classes = [EasyCoreApp::class])
@AutoConfigureMockMvc
@ContextConfiguration(initializers = [TestDatabaseInitializer::class])
@ExtendWith(DatabaseResetExtension::class)
annotation class IntegrationTest
