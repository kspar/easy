package core.ems.service.course

import core.conf.SysConf
import core.db.*
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service


private val log = KotlinLogging.logger {}


@Service
class PendingAccessSyncService {

    data class Update(val studentId: String, val studentEmail: String, val course: Long)

    @Scheduled(cron = "\${easy.core.pending-access.clean.cron}")
    fun syncAccesses() {
        val timePoint = DateTime.now().minusDays(SysConf.getProp("delete_before_days")?.toInt() ?: 14)

        transaction {
            log.debug { "Cron updating pending accesses." }

            // TODO: why add new accesses here? They should be added in the checkin service
            val accountExists =
                    Join(Account innerJoin Student, StudentPendingAccess,
                            onColumn = Account.email,
                            otherColumn = StudentPendingAccess.email,
                            joinType = JoinType.INNER)
                            .slice(Account.id, Account.email, StudentPendingAccess.course)
                            .selectAll()
                            .map {
                                Update(it[Account.id].value, it[Account.email], it[StudentPendingAccess.course].value)
                            }

            log.debug { "Found new users for linking: $accountExists" }

            StudentCourseAccess.batchInsert(accountExists) {
                this[StudentCourseAccess.student] = EntityID(it.studentId, Student)
                this[StudentCourseAccess.course] = EntityID(it.course, Course)
            }

            log.debug { "Deleting pending access for linked users" }

            for (update in accountExists) {
                StudentPendingAccess.deleteWhere {
                    StudentPendingAccess.course eq update.course and
                            (StudentPendingAccess.email eq update.studentEmail)
                }
            }

            log.debug { "Deleting pending access for older than $timePoint" }
            StudentPendingAccess.deleteWhere { StudentPendingAccess.validFrom.less(timePoint) }
        }
    }

}
