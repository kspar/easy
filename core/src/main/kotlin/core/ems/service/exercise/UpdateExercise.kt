package core.ems.service.exercise

import com.example.demo.TSLSpecFormat
import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.insertAutoExercise
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.AdocService
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryExercise
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class UpdateExercise(private val adocService: AdocService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("title", required = true) @field:NotBlank @field:Size(max = 100) val title: String,
        @JsonProperty("text_html", required = false) @field:Size(max = 300000) val textHtml: String?,
        @JsonProperty("text_adoc", required = false) @field:Size(max = 300000) val textAdoc: String?,
        @JsonProperty("grader_type", required = true) val graderType: GraderType,
        @JsonProperty("solution_file_name", required = true) val solutionFileName: String,
        @JsonProperty("solution_file_type", required = true) val solutionFileType: SolutionFileType,
        @JsonProperty("grading_script", required = false) val gradingScript: String?,
        @JsonProperty("container_image", required = false) @field:Size(max = 2000) val containerImage: String?,
        @JsonProperty("max_time_sec", required = false) val maxTime: Int?,
        @JsonProperty("max_mem_mb", required = false) val maxMem: Int?,
        @JsonProperty("assets", required = false) val assets: List<ReqAsset>?
    )

    data class ReqAsset(
        @JsonProperty("file_name", required = true) @field:Size(max = 100) val fileName: String,
        @JsonProperty("file_content", required = true) @field:Size(max = 300000) val fileContent: String
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/exercises/{exerciseId}")
    fun controller(@PathVariable("exerciseId") exIdString: String, @Valid @RequestBody req: Req, caller: EasyUser) {

        log.info { "Update exercise $exIdString by ${caller.id}" }
        val exerciseId = exIdString.idToLongOrInvalidReq()

        caller.assertAccess { libraryExercise(exerciseId, DirAccessLevel.PRAW) }

        val html = req.textAdoc?.let { adocService.adocToHtml(it) } ?: req.textHtml

        val tslContainerName = "tiivad:tsl-compose"
        val tslSpecFilename = "tsl.json"
        val tslMetaFilename = "meta.txt"

        // If TSL, get spec, compile and add resulting files to assets
        val reqModified = if (req.containerImage == tslContainerName) {
            val compileResult = compileTSLToResp(
                req.assets!!.single { it.fileName == tslSpecFilename }.fileContent,
                TSLSpecFormat.JSON
            )

            val metaStr = compileResult.meta?.let {
                """
                    Compiled at: ${it.timestamp.toString("yyyy-MM-dd HH:mm:ss")}
                    Compiler version: ${it.compilerVersion}
                    Backend: ${it.backendId} ${it.backendVersion}
                """.trimIndent()
            }
            val metaScript = listOfNotNull(metaStr?.let { ReqAsset(tslMetaFilename, it) })
            req.copy(assets = req.assets + compileResult.scripts?.map { ReqAsset(it.name, it.value) }
                .orEmpty() + metaScript)
        } else
            req

        updateExercise(exerciseId, caller.id, reqModified, html)
    }

    private fun updateExercise(exerciseId: Long, authorId: String, req: Req, html: String?) = transaction {
        val now = DateTime.now()

        val newAutoExerciseId =
            if (req.graderType == GraderType.AUTO) {
                insertAutoExercise(req.gradingScript, req.containerImage, req.maxTime, req.maxMem,
                    req.assets?.map { it.fileName to it.fileContent })

            } else null

        val lastVersionId = ExerciseVer
            .selectAll().where { ExerciseVer.exercise eq exerciseId and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.id].value }
            .first()

        ExerciseVer.update({ ExerciseVer.id eq lastVersionId }) {
            it[validTo] = now
        }

        ExerciseVer.insert {
            it[exercise] = exerciseId
            it[author] = authorId
            it[validFrom] = now
            it[previous] = lastVersionId
            it[graderType] = req.graderType
            it[solutionFileName] = req.solutionFileName
            it[solutionFileType] = req.solutionFileType
            it[title] = req.title
            it[textHtml] = html
            it[textAdoc] = req.textAdoc
            it[autoExerciseId] = newAutoExerciseId
        }

        if (html != null) {
            val inUse = StoredFile.select(StoredFile.id)
                .where { StoredFile.usageConfirmed eq false }
                .map { it[StoredFile.id].value }
                .filter { html.contains(it) }

            StoredFile.update({ StoredFile.id inList inUse }) {
                it[usageConfirmed] = true
                it[exercise] = EntityID(exerciseId, Exercise)
            }
        }
    }
}
