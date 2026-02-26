package core.ems.service.management

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.SystemConfiguration
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class AdminReadSystemConf {
    private val log = KotlinLogging.logger {}

    data class Resp(@get:JsonProperty("properties") val properties: List<PropertyResp>)

    data class PropertyResp(
        @get:JsonProperty("key") val key: String,
        @get:JsonProperty("value") val value: String?
    )

    @Secured("ROLE_ADMIN")
    @GetMapping("/system/properties")
    fun controller(caller: EasyUser): Resp {
        log.info { "Getting system properties for ${caller.id}" }
        return selectProperties()
    }

    private fun selectProperties(): Resp = transaction {
        Resp(
            SystemConfiguration
                .selectAll()
                .orderBy(SystemConfiguration.id, SortOrder.ASC)
                .map { PropertyResp(it[SystemConfiguration.id].toString(), it[SystemConfiguration.value]) })
    }
}

