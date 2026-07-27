package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.insertAutoExercise
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.AdocService
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryDir
import core.ems.service.getImplicitGroupFromAccount
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.upsertGroupDirAccess
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class CreateExercise(private val adocService: AdocService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("parent_dir_id", required = false) @field:Size(max = 100) val parentDirIdStr: String?,
        @param:JsonProperty("title", required = true) @field:NotBlank @field:Size(max = 100) val title: String,
        @param:JsonProperty("text_html", required = false) @field:Size(max = 300000) val textHtml: String?,
        @param:JsonProperty("text_adoc", required = false) @field:Size(max = 300000) val textAdoc: String?,
        @param:JsonProperty("public", required = true) val public: Boolean,
        @param:JsonProperty("anonymous_autoassess_enabled", required = true) val anonymousAutoassessEnabled: Boolean,
        @param:JsonProperty("grader_type", required = true) val graderType: GraderType,
        @param:JsonProperty("solution_file_name", required = true) val solutionFileName: String,
        @param:JsonProperty("solution_file_type", required = true) val solutionFileType: SolutionFileType,
        @param:JsonProperty("grading_script", required = false) val gradingScript: String?,
        @param:JsonProperty("container_image", required = false) @field:Size(max = 2000) val containerImage: String?,
        @param:JsonProperty("max_time_sec", required = false) val maxTime: Int?,
        @param:JsonProperty("max_mem_mb", required = false) val maxMem: Int?,
        @param:JsonProperty("assets", required = false) val assets: List<ReqAsset>?,
    )

    data class ReqAsset(
        @param:JsonProperty("file_name", required = true) @field:Size(max = 100) val fileName: String,
        @param:JsonProperty("file_content", required = true) @field:Size(max = 300000) val fileContent: String
    )

    data class Resp(@get:JsonProperty("id") val id: String)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/exercises")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser): Resp {
        log.info { "Create exercise '${dto.title}' in dir ${dto.parentDirIdStr} by ${caller.id}" }

        val parentDirId = dto.parentDirIdStr?.idToLongOrInvalidReq()

        caller.assertAccess {
            if (parentDirId != null) {
                libraryDir(parentDirId, DirAccessLevel.PRA)
            }
        }

        return when (dto.textAdoc) {
            null -> Resp(insertExercise(caller, dto, dto.textHtml, parentDirId).toString())
            else -> Resp(insertExercise(caller, dto, adocService.adocToHtml(dto.textAdoc), parentDirId).toString())
        }
    }

    private fun insertExercise(caller: EasyUser, req: Req, html: String?, parentDirId: Long?): Long = transaction {
        val teacherId = caller.id
        val now = DateTime.now()

        val newAutoExerciseId =
            if (req.graderType == GraderType.AUTO) {
                insertAutoExercise(
                    req.gradingScript, req.containerImage, req.maxTime, req.maxMem,
                    req.assets?.map { it.fileName to it.fileContent })

            } else null

        val implicitDirId = Dir.insertAndGetId {
            // ChickenEgg: name = exercise ID but that's not known yet
            it[name] = "create-ex-placeholder"
            it[isImplicit] = true
            if (parentDirId != null) {
                it[parentDir] = EntityID(parentDirId, Dir)
            }
            it[createdAt] = now
            it[modifiedAt] = now
        }

        upsertGroupDirAccess(getImplicitGroupFromAccount(caller.id), implicitDirId.value, DirAccessLevel.PRAWM)

        val exerciseId = Exercise.insertAndGetId {
            it[owner] = teacherId
            it[public] = req.public
            it[anonymousAutoassessEnabled] = req.anonymousAutoassessEnabled
            it[dir] = implicitDirId
            it[createdAt] = now
        }

        // Set correct name for implicit dir
        Dir.update({ Dir.id eq implicitDirId }) {
            it[name] = exerciseId.value.toString()
        }

        ExerciseVer.insert {
            it[exercise] = exerciseId
            it[author] = teacherId
            it[validFrom] = now
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
                it[exercise] = exerciseId
            }
        }

        exerciseId.value
    }
}
