package core.ems.service.exercise

import com.example.demo.TSLSpecFormat
import com.example.demo.normalizeTSLSpec
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.ObjectMapper
import core.conf.security.EasyUser
import core.db.Asset
import core.db.AutoExercise
import core.db.ExerciseVer
import core.db.GraderType
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The container image whose exercises are authored as a TSL spec rather than as scripts. */
const val TSL_CONTAINER_IMAGE = "tiivad:tsl-compose"

/** The asset holding the spec, and the one holding the record of how it was compiled. */
private const val TSL_SPEC_ASSET = "tsl.json"
private const val TSL_META_ASSET = "meta.txt"
private const val GENERATED_PREFIX = "generated_"

/**
 * Regenerate the Python that TSL exercises grade with, from specs that have not changed.
 *
 * ### Why this needs to exist at all
 *
 * A TSL exercise stores its spec as a `tsl.json` asset; saving it compiles that spec and stores the
 * result as a `generated_0.py` asset beside it. **The compilation happens once, at save time.** So a
 * fix to the compiler reaches nothing that already exists — every exercise keeps grading with the
 * output of whichever compiler was running the day a teacher last pressed save.
 *
 * That was survivable while compiler changes were rare. EZ-1774 is the demonstration that it is not:
 * a defect that made every check dictionary unusable sat in the compiler for nine days, and fixing
 * it did nothing for the several hundred exercises already compiled by it. Re-saving each one is the
 * only tool that existed, and re-saving is wrong here for a reason worth being precise about — **it
 * creates a new exercise version, and nothing about the exercise changed.** The author did not edit
 * it. The version chain is a record of authoring, and filling it with entries nobody authored makes
 * it a worse record of what teachers actually did.
 *
 * ### What it does instead
 *
 * Recompiles the spec and replaces the generated assets **on the existing `automatic_exercise`
 * row**. No new `exercise_version`, no new `automatic_exercise`, no change to any asset the
 * teacher wrote, and — unless `normalize_specs` is set, and then only for a spec strict JSON
 * rejects — no change to `tsl.json`. `ExerciseVer.autoExerciseId` still points where it did.
 *
 * Only **current** versions — `valid_to IS NULL` — because those are the only ones that can grade a
 * submission. An older version's stored script is a record of what that version generated, and
 * rewriting it would be editing history to say something that was never true.
 *
 * ### Dry run by default
 *
 * `apply` defaults to false, and the response is the same either way, so the report is a rehearsal
 * of the write rather than a different code path describing it. Same shape as `StoredFileSweep`, for
 * the same reason: this rewrites the thing that decides students' grades, in bulk, and reading the
 * list first has to be the easy option.
 *
 * A recompile that produces byte-identical output is reported as unchanged and **writes nothing** —
 * including `meta.txt`, whose timestamp would otherwise make every run claim it had changed
 * everything and make the report useless for spotting the exercises that matter.
 *
 * ### A side effect worth knowing about: duplicate assets are collapsed
 *
 * `asset` has no unique constraint on `(auto_exercise_id, file_name)`, and two rows with the same
 * name is a state this codebase has reached before — `doc/core/tsl-migration/RUNBOOK.md` warns that
 * sending back the `generated_0.py` a GET returned stores it twice, once stale. Which of the two the
 * executor then writes to disk is unspecified, so such an exercise may already be grading with the
 * stale copy.
 *
 * Deleting by name and inserting one row therefore repairs that rather than merely overwriting it.
 * It is a repair and not a data loss: the two rows were the same file, and only one of them could
 * ever have been used.
 */
@RestController
@RequestMapping("/v2")
class AdminRecompileTslController {
    private val log = KotlinLogging.logger {}

    data class Req(
        /** False rehearses and writes nothing. The default is deliberate; see the class docblock. */
        @param:JsonProperty("apply") val apply: Boolean = false,
        /** Optional filter. Absent means every current TSL exercise. */
        @param:JsonProperty("exercise_ids") val exerciseIds: List<Long>? = null,
        /**
         * Also rewrite `tsl.json` itself — but only where the stored text is not strict JSON.
         *
         * The wui-era editor could save a spec with a raw newline inside a string. kotlinx's
         * parser tolerates that, so the exercise compiles and grades — but the React editor loads
         * with `JSON.parse`, which is strict, so the teacher gets a raw parser error and can never
         * open it (EZ-1813). Decoding with the same parser the compiler uses and re-encoding is
         * exactly the normalisation saving in the editor would have performed, had it been able to
         * load. A spec that already parses strictly is never rewritten, whatever its formatting.
         */
        @param:JsonProperty("normalize_specs") val normalizeSpecs: Boolean = false,
    )

    data class Resp(
        @get:JsonProperty("applied") val applied: Boolean,
        @get:JsonProperty("scanned") val scanned: Int,
        @get:JsonProperty("changed") val changed: Int,
        @get:JsonProperty("unchanged") val unchanged: Int,
        @get:JsonProperty("failed") val failed: Int,
        /**
         * Present so the four outcome counts sum to [scanned].
         *
         * Without it a skipped exercise leaves an unexplained gap in a report whose whole job is to
         * be reconciled before someone runs it again with `apply`.
         */
        @get:JsonProperty("skipped") val skipped: Int,
        /**
         * Ids given in `exercise_ids` that are not a current TSL exercise.
         *
         * Without this, asking for an exercise that has been retired, renumbered or was never TSL
         * returns `scanned: 0` — which is indistinguishable from "checked it, nothing to do". For a
         * tool whose whole safety story is reading the dry run first, "I asked about 40 and it
         * looked at 12" has to be visible rather than inferred.
         */
        @get:JsonProperty("not_found") val notFound: List<Long>,
        @get:JsonProperty("results") val results: List<ExerciseResp>,
    )

    data class ExerciseResp(
        @get:JsonProperty("exercise_id") val exerciseId: Long,
        @get:JsonProperty("outcome") val outcome: Outcome,
        /**
         * Which assets this run touches: the generated scripts that differ or are stale — and,
         * under `normalize_specs` (or when identical duplicate rows are collapsed), `tsl.json`
         * itself. Empty when nothing changed.
         */
        @get:JsonProperty("scripts") val scripts: List<String>,
        /** Why, for FAILED and SKIPPED. */
        @get:JsonProperty("detail") val detail: String?,
    )

    enum class Outcome {
        /** The recompiled script differs from the stored one. Rewritten if `apply` was true. */
        CHANGED,

        /** Byte-identical output. Nothing written, not even the meta timestamp. */
        UNCHANGED,

        /** The spec no longer compiles. **The stored script is left exactly as it was.** */
        FAILED,

        /** A TSL container with no `tsl.json` — nothing to recompile from. */
        SKIPPED,
    }

    @Secured("ROLE_ADMIN")
    @PostMapping("/admin/exercises/tsl/recompile")
    fun controller(@Valid @RequestBody req: Req, caller: EasyUser): Resp {
        log.info {
            "${caller.id} is recompiling TSL exercises (apply=${req.apply}, " +
                    "ids=${req.exerciseIds?.size?.let { "$it given" } ?: "all"})"
        }

        val targets = selectCurrentTslExercises(req.exerciseIds)
        // One transaction per exercise rather than one for the run. A run over several hundred
        // exercises should not be all-or-nothing: a spec that fails to compile is reported and the
        // rest proceed, and an interrupted run leaves the exercises it already did in a good state
        // rather than rolling every one of them back.
        val results = targets.map { recompileOne(it, req.apply, req.normalizeSpecs) }

        val resp = Resp(
            applied = req.apply,
            scanned = results.size,
            changed = results.count { it.outcome == Outcome.CHANGED },
            unchanged = results.count { it.outcome == Outcome.UNCHANGED },
            failed = results.count { it.outcome == Outcome.FAILED },
            skipped = results.count { it.outcome == Outcome.SKIPPED },
            notFound = req.exerciseIds.orEmpty().filterNot { id -> targets.any { it.exerciseId == id } }.sorted(),
            results = results,
        )
        log.info {
            "TSL recompile ${if (req.apply) "applied" else "(DRY RUN)"}: ${resp.scanned} scanned, " +
                    "${resp.changed} changed, ${resp.unchanged} unchanged, ${resp.failed} failed, " +
                    "${resp.skipped} skipped" +
                    if (resp.notFound.isEmpty()) "" else ", ${resp.notFound.size} id(s) not a current TSL exercise"
        }
        return resp
    }

    /**
     * [gradingScript] is carried so the stale-asset sweep can tell whether a `generated_*.py` it is
     * about to delete is the one the exercise actually runs. See [recompileWithin].
     */
    internal data class Target(val exerciseId: Long, val autoExerciseId: Long, val gradingScript: String)

    /**
     * Current versions only, and only those on the TSL container.
     *
     * `validTo IS NULL` is what makes this "the version that grades". A join through `AutoExercise`
     * rather than a check on the assets, because the container image is what decides whether an
     * exercise is TSL-authored — an exercise with a stray `tsl.json` and a different image is not.
     */
    private fun selectCurrentTslExercises(only: List<Long>?): List<Target> = transaction {
        (ExerciseVer innerJoin AutoExercise)
            .select(ExerciseVer.exercise, ExerciseVer.autoExerciseId, AutoExercise.gradingScript)
            .where {
                ExerciseVer.validTo.isNull() and
                        (ExerciseVer.graderType eq GraderType.AUTO) and
                        (AutoExercise.containerImage eq TSL_CONTAINER_IMAGE)
            }
            .map {
                Target(
                    it[ExerciseVer.exercise].value,
                    it[ExerciseVer.autoExerciseId]!!.value,
                    it[AutoExercise.gradingScript],
                )
            }
            .let { all -> if (only == null) all else all.filter { it.exerciseId in only } }
            .sortedBy { it.exerciseId }
    }

    /**
     * One exercise, in its own transaction, and **nothing it can throw ends the run**.
     *
     * `Throwable`, not `Exception`, and around the whole thing rather than around the compile.
     * `CompileSpecTree` catches `Throwable` for a reason it wrote down — a spec deep enough to blow
     * the stack "is a failure to report, not a reason to abandon the other seven hundred" — and the
     * same applies here with an extra edge: every exercise commits separately, so an escape leaves
     * the admin with a 500 and **no record of the hundreds already rewritten**. A run that cannot
     * say what it did is worse than one that did less.
     */
    private fun recompileOne(target: Target, apply: Boolean, normalizeSpecs: Boolean): ExerciseResp = try {
        transaction { recompileWithin(target, apply, normalizeSpecs) }
    } catch (e: Throwable) {
        log.warn(e) { "TSL recompile threw for exercise ${target.exerciseId}" }
        ExerciseResp(target.exerciseId, Outcome.FAILED, emptyList(), describe(e))
    }

    /**
     * `internal` so a test can hand it a [Target] that is deliberately out of date.
     *
     * The staleness it guards against arises from a *concurrent* save during a minutes-long run, and
     * driving the endpoint cannot produce that interleaving — a single request scans and writes with
     * nothing in between. Calling this directly with a target that has already been superseded is
     * the only way to make the guard's own branch execute, and a guard whose branch never runs in a
     * test is a guard nobody has checked.
     */
    internal fun recompileWithin(target: Target, apply: Boolean, normalizeSpecs: Boolean = false): ExerciseResp {
        // Re-read the current version inside this transaction, and stop if it has moved.
        //
        // The target list is resolved once, up front, and a run over several hundred exercises takes
        // minutes. A teacher saving in that window makes UpdateExercise insert a *new*
        // automatic_exercise and re-point the current version at it — after which target's row is
        // the superseded one. Writing to it would be exactly the history rewrite this endpoint
        // promises never to do, while the version that actually grades keeps its stale script and
        // the report claims success.
        val stillCurrent = ExerciseVer
            .select(ExerciseVer.autoExerciseId)
            .where { (ExerciseVer.exercise eq target.exerciseId) and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.autoExerciseId]?.value }
            .singleOrNull()

        if (stillCurrent != target.autoExerciseId) {
            return ExerciseResp(
                target.exerciseId, Outcome.SKIPPED, emptyList(),
                "the exercise was saved while this run was in progress; recompile again",
            )
        }

        val rows = Asset
            .select(Asset.fileName, Asset.fileContent)
            .where { Asset.autoExercise eq target.autoExerciseId }
            .map { it[Asset.fileName] to it[Asset.fileContent] }

        val assets = rows.toMap()
        // How many rows carry each name. `asset` has no unique constraint, and which of two rows
        // with the same name `toMap()` keeps is arbitrary — so a duplicate whose surviving copy
        // happens to match the fresh output would otherwise be reported UNCHANGED and left in place,
        // with the executor still free to write the other one to disk.
        val rowsPerName = rows.groupingBy { it.first }.eachCount()

        val spec = assets[TSL_SPEC_ASSET]
            ?: return ExerciseResp(
                target.exerciseId, Outcome.SKIPPED, emptyList(),
                "no $TSL_SPEC_ASSET asset on a $TSL_CONTAINER_IMAGE exercise",
            )

        val compiled = try {
            compileTSLToResp(spec, TSLSpecFormat.JSON)
        } catch (e: Exception) {
            // Reported, never fatal, and above all never destructive: an exercise whose spec no
            // longer compiles keeps the script it has, which still grades, rather than being left
            // with none.
            log.warn(e) { "TSL recompile failed for exercise ${target.exerciseId}" }
            return ExerciseResp(target.exerciseId, Outcome.FAILED, emptyList(), describe(e))
        }

        val fresh = compiled.scripts.orEmpty().associate { it.name to it.value }

        // A compile that produced no script is a failure, not an instruction to delete every script.
        // `scripts` is nullable and `CompileTSL.controller` returns exactly that on a failed
        // compile, so the shape is reachable — and the consequence would be the one outcome this
        // whole file is built to avoid, applied to every exercise in the run.
        if (fresh.isEmpty()) {
            return ExerciseResp(
                target.exerciseId, Outcome.FAILED, emptyList(),
                "the compiler returned no scripts; refusing to remove the ones that are there",
            )
        }

        // A generated script the grading command still names is not stale, whatever the compiler
        // emits now. Deleting it would leave the exercise unable to grade — the same end state the
        // FAILED path is careful to avoid, reached through the one destructive step.
        val obsolete = assets.keys.filter { it.startsWith(GENERATED_PREFIX) && it !in fresh }
        val (referenced, stale) = obsolete.partition { it in target.gradingScript }

        // Duplicate tsl.json rows with *differing* content are a state this endpoint must not
        // resolve by picking one: `rows.toMap()` keeps an arbitrary copy, and canonicalising an
        // arbitrary choice under normalize_specs would silently decide which spec the exercise
        // is. Identical copies are the same repair as duplicated generated assets.
        val specCopies = rows.filter { it.first == TSL_SPEC_ASSET }.map { it.second }
        if (specCopies.distinct().size > 1) {
            return ExerciseResp(
                target.exerciseId, Outcome.FAILED, emptyList(),
                "several $TSL_SPEC_ASSET rows with differing content; resolve by hand before recompiling",
            )
        }

        // See Req.normalizeSpecs. Non-null only when the stored spec is not strict JSON — the
        // compile above already proved the compiler's own parser accepts it, so decode-and-encode
        // cannot lose anything a strict save would have kept.
        val normalizedSpec = if (normalizeSpecs && !parsesAsStrictJson(spec)) {
            normalizeTSLSpec(spec).takeIf { it != spec }
        } else null

        // Everything this run contributes to tsl.json, spelled once so the report, the delete and
        // the insert cannot disagree: a normalisation, or the collapse of identical duplicates.
        val specRewrite: Map<String, String> = when {
            normalizedSpec != null -> mapOf(TSL_SPEC_ASSET to normalizedSpec)
            specCopies.size > 1 -> mapOf(TSL_SPEC_ASSET to spec)
            else -> emptyMap()
        }

        // Only the generated scripts decide whether anything changed. meta.txt carries a timestamp
        // that differs on every run, so including it would report every exercise as changed on every
        // run and the number would stop meaning anything.
        val differing = fresh.filter { (name, value) -> assets[name] != value }.keys
        val duplicated = (fresh.keys + obsolete).filter { (rowsPerName[it] ?: 0) > 1 }

        val touched = (differing + stale + duplicated + specRewrite.keys).toSortedSet().toList()
        if (touched.isEmpty()) {
            val note = "kept, still named by the grading script: $referenced".takeIf { referenced.isNotEmpty() }
            return ExerciseResp(target.exerciseId, Outcome.UNCHANGED, emptyList(), note)
        }

        if (apply) {
            // Replace exactly the generated assets and the meta record — plus, only under
            // normalizeSpecs and only for a spec strict JSON rejects, tsl.json itself. Anything
            // else the teacher attached is never touched.
            val toReplace = fresh.keys + stale + TSL_META_ASSET + specRewrite.keys
            Asset.deleteWhere { (Asset.autoExercise eq target.autoExerciseId) and (Asset.fileName inList toReplace) }

            val meta = compiled.meta?.let {
                """
                    Compiled at: ${it.timestamp.toString("yyyy-MM-dd HH:mm:ss")}
                    Compiler version: ${it.compilerVersion}
                    Backend: ${it.backendId} ${it.backendVersion}
                """.trimIndent()
            }
            (fresh + listOfNotNull(meta?.let { TSL_META_ASSET to it }) + specRewrite)
                .forEach { (name, content) ->
                Asset.insert {
                    it[autoExercise] = target.autoExerciseId
                    it[fileName] = name
                    it[fileContent] = content
                }
            }
            log.info { "Recompiled exercise ${target.exerciseId}, rewrote $touched" }
        }

        val note = "kept, still named by the grading script: $referenced".takeIf { referenced.isNotEmpty() }
        return ExerciseResp(target.exerciseId, Outcome.CHANGED, touched, note)
    }

    /** The first line of why something failed, short enough to sit in a list of several hundred. */
    private fun describe(e: Throwable): String =
        (e.message ?: e::class.simpleName).orEmpty().lines().first().take(300)

    /**
     * Whether [text] is JSON by ECMA-404's rules — the rules `JSON.parse` in the editor applies.
     * Jackson's defaults are those rules (unescaped control characters and missing delimiters are
     * both rejected), which is precisely the disagreement with kotlinx that normalizeSpecs exists
     * to repair, so Jackson is the honest oracle here.
     */
    private fun parsesAsStrictJson(text: String): Boolean = try {
        strictJson.readTree(text)
        true
    } catch (_: Exception) {
        false
    }

    private val strictJson = ObjectMapper()
}
