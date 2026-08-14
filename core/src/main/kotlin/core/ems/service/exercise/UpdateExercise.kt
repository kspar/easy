package core.ems.service.exercise

import com.example.demo.TSLSpecFormat
import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.insertAutoExercise
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.MarkdownService
import core.ems.service.rejectLegacyContentFields
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryExercise
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class UpdateExercise(private val markdownService: MarkdownService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("title", required = true) @field:NotBlank @field:Size(max = 100) val title: String,
        @param:JsonProperty("text_md", required = false) @field:Size(max = 300000) val textMd: String?,
        // Rejected, never read — see rejectLegacyContentFields (EZ-1730).
        @param:JsonProperty("text_adoc", required = false) val legacyTextAdoc: String? = null,
        @param:JsonProperty("text_html", required = false) val legacyTextHtml: String? = null,
        @param:JsonProperty("grader_type", required = true) val graderType: GraderType,
        @param:JsonProperty("solution_file_name", required = true) val solutionFileName: String,
        @param:JsonProperty("solution_file_type", required = true) val solutionFileType: SolutionFileType,
        @param:JsonProperty("grading_script", required = false) val gradingScript: String?,
        @param:JsonProperty("container_image", required = false) @field:Size(max = 2000) val containerImage: String?,
        @param:JsonProperty("max_time_sec", required = false) val maxTime: Int?,
        @param:JsonProperty("max_mem_mb", required = false) val maxMem: Int?,
        @param:JsonProperty("assets", required = false) val assets: List<ReqAsset>?
    )

    data class ReqAsset(
        @param:JsonProperty("file_name", required = true) @field:Size(max = 100) val fileName: String,
        @param:JsonProperty("file_content", required = true) @field:Size(max = 300000) val fileContent: String
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/exercises/{exerciseId}")
    fun controller(@PathVariable("exerciseId") exIdString: String, @Valid @RequestBody req: Req, caller: EasyUser) {

        log.info { "Update exercise $exIdString by ${caller.id}" }
        val exerciseId = exIdString.idToLongOrInvalidReq()

        val (reqModified, html) = prepare(exerciseId, req, caller)
        updateExercise(exerciseId, caller.id, reqModified, html, preserveAttribution = false)
    }

    /**
     * Rewrites an exercise's content without changing who last edited it, or when.
     *
     * The same replace as [controller] — it creates a version, so the previous one stays in the
     * database and a single exercise can be rolled back by making it current again — but the new
     * row takes the previous version's `author`, and its `valid_from` plus a millisecond, instead
     * of stamping the caller and now. Those two columns are the only source of `last_modified` and
     * `last_modified_by_id` (see ReadExercise, TeacherReadExerciseDetails and the library listing
     * in ReadDir), so a rewrite is invisible on every surface that shows them.
     *
     * `valid_to` on the superseded row is still set to now, and deliberately: it is the only
     * record that a rewrite happened at all, and ordering comes from `valid_from`, so the
     * overlapping interval costs nothing and no query in core asks about one.
     *
     * That is what a mechanical change to a thousand exercises needs. The plain PUT would hand
     * every one of them to whoever ran the script, date them all today, and reorder any list
     * sorted by modification time — telling every teacher their exercises were edited by an admin
     * on a day nobody touched them. Keeping the version is the other half: the rewrite is
     * reversible per exercise, which is what makes running one unhurried.
     *
     * Admin-only, because that is exactly the power to edit someone's exercise without leaving a
     * mark on it.
     *
     * Two things it carries forward that the plain PUT drops, both because a migration must change
     * only what it was asked to:
     *
     * - `text_adoc`, the pre-EZ-1729 source. Nothing writes or renders it any more, but for
     *   anything authored in adoc the current version's row is the only copy, and the Markdown
     *   conversion is still being re-read from it.
     * - text itself. `text_html` is regenerated from `text_md` on every save, so a request whose
     *   `text_md` is null blanks an exercise that has rendered text and no Markdown source — a
     *   real shape, for everything authored before EZ-1731. A rewrite refuses rather than quietly
     *   emptying it, since the caller is a script and nobody is watching.
     */
    @Secured("ROLE_ADMIN")
    @PutMapping("/admin/exercises/{exerciseId}/rewrite")
    fun rewriteController(
        @PathVariable("exerciseId") exIdString: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {
        log.info { "Rewrite exercise $exIdString by ${caller.id}, preserving attribution" }
        val exerciseId = exIdString.idToLongOrInvalidReq()

        val (reqModified, html) = prepare(exerciseId, req, caller)
        updateExercise(exerciseId, caller.id, reqModified, html, preserveAttribution = true)
    }

    /** Validation, Markdown rendering and TSL compilation — everything both paths do identically. */
    private fun prepare(exerciseId: Long, req: Req, caller: EasyUser): Pair<Req, String?> {

        caller.assertAccess { libraryExercise(exerciseId, DirAccessLevel.PRAW) }

        rejectLegacyContentFields("text_md", "text_adoc" to req.legacyTextAdoc, "text_html" to req.legacyTextHtml)

        val html = req.textMd?.let { markdownService.mdToHtml(it) }

        val tslContainerName = "tiivad:tsl-compose"
        val tslSpecFilename = "tsl.json"
        val tslMetaFilename = "meta.txt"

        // If TSL, get spec, compile and add resulting files to assets
        val reqModified = if (req.containerImage == tslContainerName) {
            // Everything in this block used to be able to throw its way to a 500 with no usable
            // message: `assets!!` on a request without them, `single` on none or several, and the
            // compiler itself. All four are the author's spec being wrong, which is a 400.
            val specs = req.assets.orEmpty().filter { it.fileName == tslSpecFilename }
            val spec = when (specs.size) {
                1 -> specs.single()
                0 -> throw InvalidRequestException(
                    "A $tslContainerName exercise must have a $tslSpecFilename asset",
                    ReqError.TSL_COMPILE_FAILED, notify = false
                )

                else -> throw InvalidRequestException(
                    "Expected one $tslSpecFilename asset, got ${specs.size}",
                    ReqError.TSL_COMPILE_FAILED, notify = false
                )
            }

            val compileResult = try {
                compileTSLToResp(spec.fileContent, TSLSpecFormat.JSON)
            } catch (e: Exception) {
                // notify = false: a spec the compiler rejects is a teacher's mistake, not an
                // outage. Notifying would mean an admin email per bad save — and a storm of them
                // during the EZ-1607 spec migration, which re-saves every TSL exercise.
                log.debug(e) { "TSL compilation failed for exercise $exerciseId" }
                throw InvalidRequestException(
                    "TSL compilation failed: ${e.message}",
                    ReqError.TSL_COMPILE_FAILED, notify = false
                )
            }

            val metaStr = compileResult.meta?.let {
                """
                    Compiled at: ${it.timestamp.toString("yyyy-MM-dd HH:mm:ss")}
                    Compiler version: ${it.compilerVersion}
                    Backend: ${it.backendId} ${it.backendVersion}
                """.trimIndent()
            }
            val metaScript = listOfNotNull(metaStr?.let { ReqAsset(tslMetaFilename, it) })
            // orEmpty() rather than !!: the null case is now rejected above with a message, so
            // this no longer needs to be the thing that throws.
            req.copy(assets = req.assets.orEmpty() + compileResult.scripts?.map { ReqAsset(it.name, it.value) }
                .orEmpty() + metaScript)
        } else
            req

        return reqModified to html
    }

    private fun updateExercise(
        exerciseId: Long,
        authorId: String,
        req: Req,
        html: String?,
        preserveAttribution: Boolean,
    ) = transaction {
        val now = DateTime.now()

        val newAutoExerciseId =
            if (req.graderType == GraderType.AUTO) {
                insertAutoExercise(
                    req.gradingScript, req.containerImage, req.maxTime, req.maxMem,
                    req.assets?.map { it.fileName to it.fileContent })

            } else null

        val previousVer = ExerciseVer
            .selectAll().where { ExerciseVer.exercise eq exerciseId and ExerciseVer.validTo.isNull() }
            .first()

        val lastVersionId = previousVer[ExerciseVer.id].value

        // Regenerating text_html from text_md means a request without text_md empties an exercise
        // that has only rendered text. A person doing this in the editor sees it happen; a script
        // rewriting a thousand exercises does not, so refuse instead.
        if (preserveAttribution && html == null && !previousVer[ExerciseVer.textHtml].isNullOrEmpty()) {
            throw InvalidRequestException(
                "Rewriting exercise $exerciseId without text_md would blank its text, " +
                        "since text_html is regenerated from text_md. It has no Markdown source to send back.",
                ReqError.INVALID_PARAMETER_VALUE, notify = false
            )
        }

        ExerciseVer.update({ ExerciseVer.id eq lastVersionId }) {
            it[validTo] = now
        }

        ExerciseVer.insert {
            it[exercise] = exerciseId
            // A rewrite keeps the previous version's attribution, so the change is invisible to
            // every read — all of which resolve the current version by `valid_to IS NULL` rather
            // than by asking which version was valid at some instant.
            it[author] = if (preserveAttribution) previousVer[ExerciseVer.author] else EntityID(authorId, Account)
            // A millisecond later, not the same instant. Copying valid_from exactly would leave
            // two rows in the chain that `ORDER BY valid_from` cannot separate, and version
            // history is the thing this endpoint exists to keep usable. A millisecond sorts
            // correctly and is below the resolution of anything that displays it.
            it[validFrom] =
                if (preserveAttribution) previousVer[ExerciseVer.validFrom].plusMillis(1) else now
            it[previous] = lastVersionId
            it[graderType] = req.graderType
            it[solutionFileName] = req.solutionFileName
            it[solutionFileType] = req.solutionFileType
            it[title] = req.title
            it[textHtml] = html
            it[textMd] = req.textMd
            // Read-only and unreachable through the API, so a rewrite is the one write that must
            // carry it: for an adoc-authored exercise this row holds the only copy of the source.
            it[textAdoc] = if (preserveAttribution) previousVer[ExerciseVer.textAdoc] else null
            it[autoExerciseId] = newAutoExerciseId
        }
    }
}
