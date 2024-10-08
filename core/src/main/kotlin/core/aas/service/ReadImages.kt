package core.aas.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.ContainerImage
import mu.KotlinLogging
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadImagesController {
    private val log = KotlinLogging.logger {}

    data class Resp(@JsonProperty("id") val id: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/container-images")
    fun controller(caller: EasyUser): List<Resp> {
        log.info { "Getting images for ${caller.id}" }
        return selectAllImages()
    }

    private fun selectAllImages(): List<Resp> = transaction {
        ContainerImage
            .selectAll()
            .sortedBy { ContainerImage.id }
            .map { Resp(it[ContainerImage.id].value) }
    }
}
