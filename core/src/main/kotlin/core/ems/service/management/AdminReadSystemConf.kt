package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.SystemConfiguration
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class AdminReadSystemConf {
    private val log = KotlinLogging.logger {}

    data class Resp(@JsonProperty("properties") val properties: List<PropertyResp>)

    data class PropertyResp(
        @JsonProperty("key") val key: String,
        @JsonProperty("value") val value: String?
    )

    @Secured("ROLE_ADMIN")
    @GetMapping("/system/properties")
    fun controller(caller: EasyUser): Resp {
        log.info { "Getting system properties for ${caller.id}" }
        return selectProperties()
    }

    private fun selectProperties(): Resp = transaction {
        Resp(SystemConfiguration
            .selectAll()
            .orderBy(SystemConfiguration.id, SortOrder.ASC)
            .map { PropertyResp(it[SystemConfiguration.id].toString(), it[SystemConfiguration.value]) })
    }
}

