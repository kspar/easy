package core.conf

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * Fails if `application.yaml.sample` cannot satisfy a placeholder the code requires.
 *
 * A `@Value` with no default is not a feature flag. Spring resolves it while creating the bean, so a
 * key the config does not have is a `PlaceholderResolutionException`, a failed context, and a core
 * that does not start — the same failure class as an edited changeset, arrived at from the other
 * side. EZ-1786 added seven `easy.core.youtrack.*` keys and the sample gained six of them; `token`
 * was left out because it lives in secrets.yaml, which is also true of `keycloak.client-secret` and
 * `moodle-sync.wstoken` and does not stop either of those from being a placeholder here.
 *
 * That omission is invisible until someone pays for it. The real `application.yaml` is gitignored, so
 * every developer's copy is a hand-made thing that drifts the moment a feature adds a property, and
 * this file is the only description of what a complete one looks like. Being one key short of
 * bootable is therefore not a documentation gap — it is a broken setup step that reports itself as a
 * stack trace on someone's first morning.
 *
 * **Placeholders carrying a default are deliberately not checked.** A reference like
 * `easy.core.db.test-data:false` cannot fail a boot, so requiring it here would ask the sample to
 * list every tunable rather than every necessity, and a sample nobody can read is a sample nobody
 * copies.
 *
 * ### What this does not cover
 *
 * The deployed config comes from `roles/core_config`'s Jinja template plus the secrets that role
 * injects, not from this file, so a template missing a new key would still reach production
 * unnoticed. Covering it means teaching a test which absences are legitimate — precisely the keys in
 * that role's injection list — and that coupling is worth more thought than this test needs. The
 * role asserts on leftover placeholders at the end of its run, which is a different guard for a
 * neighbouring problem, not this one.
 */
class SampleConfigCompletenessTest {

    /**
     * Matches a backslash, then a dollar, then a braced property name.
     *
     * The backslash is the whole trick. A Spring placeholder in Kotlin source has to be escaped to
     * survive the compiler, which is exactly what separates a config reference from ordinary string
     * interpolation — and the latter appears on hundreds of log lines. Matching without the
     * backslash finds every interpolated `caller.id` in the codebase and reports them as missing
     * configuration, which is how the first draft of this scan produced 111 findings and no signal.
     *
     * The character class excludes `:`, and that is what leaves defaulted placeholders out.
     */
    private val required = Regex("""\\\${'$'}\{([A-Za-z0-9_.\-]+)\}""")

    /**
     * Comment lines do not count, for the same reason [core.testing.NoWallClockInFixturesTest] skips
     * them: this codebase explains itself in prose, and a KDoc that quotes a property name is
     * documentation rather than a requirement.
     */
    private fun isComment(line: String): Boolean =
        line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    @Test
    fun `the sample config provides every property the code requires`() {
        val sources = File("src/main/kotlin")
        val sample = File("src/main/resources/application.yaml.sample")
        assertTrue(sources.isDirectory && sample.isFile) {
            "Expected main sources at ${sources.absolutePath} and the sample at ${sample.absolutePath}" +
                    " — this test reads both from disk, so it depends on the working directory being " +
                    "the core module."
        }

        val placeholders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filterNot { (_, line) -> isComment(line) }
                    .flatMap { (i, line) ->
                        required.findAll(line).map { it.groupValues[1] to "${relative(file)}:${i + 1}" }
                    }
            }
            .groupBy({ it.first }, { it.second })

        // A scan that finds nothing agrees with every sample there could be. Same reasoning as the
        // file count in NoWallClockInFixturesTest: the failure mode of a source-reading guard is
        // reading no sources, and it looks identical to success.
        assertTrue(placeholders.size >= 25) {
            "Only ${placeholders.size} required placeholders found under ${sources.absolutePath} — " +
                    "the walk or the pattern is broken, and this test would then pass against anything."
        }

        // Spring's own loader rather than a YAML parser, so "the sample has this key" is decided by
        // the same flattening that will decide it at boot: nested maps become dotted names, and an
        // empty string is a value.
        val provided = YamlPropertySourceLoader().load("sample", FileSystemResource(sample))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { it.propertyNames.asList() }
            .toSet()

        val missing = placeholders.keys.filterNot { it in provided }.sorted()

        assertTrue(missing.isEmpty()) {
            "${sample.name} is missing ${missing.size} of the ${placeholders.size} properties the " +
                    "code requires with no default:\n" +
                    missing.joinToString("\n") { key ->
                        "  $key\n" + placeholders.getValue(key).joinToString("\n") { "      $it" }
                    } +
                    "\n\nEach of these is a `@Value` or `@Scheduled` with no fallback, so a config " +
                    "without it fails the Spring context and core does not start. Add it to the " +
                    "sample with a placeholder value — a secret that Ansible injects still belongs " +
                    "here, because this file is what a developer copies to application.yaml and no " +
                    "Ansible run will visit that copy."
        }
    }

    private fun relative(file: File) = file.invariantSeparatorsPath.substringAfter("src/main/kotlin/")
}
