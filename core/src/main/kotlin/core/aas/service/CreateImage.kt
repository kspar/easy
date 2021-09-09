package core.aas.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.ContainerImage
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid
import javax.validation.constraints.NotBlank

@RestController
@RequestMapping("/v2")
class CreateImageController {

    data class Req(@JsonProperty("id", required = true) @field:NotBlank val id: String)

    data class Resp(@JsonProperty("id") val id: String)

    @Secured("ROLE_ADMIN")
    @PostMapping("/container-images")
    fun controller(@Valid @RequestBody body: Req): Resp {
        return Resp(createContainerImage(body))
    }
}

private fun createContainerImage(newContainer: CreateImageController.Req): String {
    return transaction {

        if (ContainerImage.select { ContainerImage.id eq newContainer.id }.count() == 0L) {
            ContainerImage.insertAndGetId { it[id] = newContainer.id }.value

        } else {
            throw InvalidRequestException(
                "Container image '${newContainer.id}' already exists.",
                ReqError.ENTITY_WITH_ID_ALREADY_EXISTS,
                "id" to newContainer.id
            )
        }
    }
}
