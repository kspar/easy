package core.ems.cron

import core.conf.SysConf
import core.db.StudentPendingAccess
import mu.KotlinLogging
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service


private val log = KotlinLogging.logger {}


@Service
class CleanPendingAccessesCron {
    @Scheduled(cron = "\${easy.core.pending-access.clean.cron}")
    fun deleteOldPendingAccesses() {
        val deleteOlderThan = DateTime.now().minusDays(
                SysConf.getProp("delete_before_days")?.toInt() ?: 90)
        log.debug { "Deleting pending access created earlier than $deleteOlderThan" }
        transaction {
            StudentPendingAccess.deleteWhere { StudentPendingAccess.validFrom.less(deleteOlderThan) }
        }
    }
}
