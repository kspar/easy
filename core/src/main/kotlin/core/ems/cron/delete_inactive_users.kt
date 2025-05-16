package core.ems.cron


import core.db.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.*


@Component
class DeleteInactiveUsers {
    // Chosen by fair dice roll, guaranteed to be random - do not change
    private val defaultUser = "58060066-c4f9-4054-88be-c8492bb8e487"

    private val log = KotlinLogging.logger {}


    // TODO: once a day, for now every 30 seconds for testing
    @Scheduled(cron = "*/30 * * * * *")
    fun cron() {
        transaction {
            val twoYearsAgo = DateTime.now().minusYears(2)
            val fiveYearsAgo = DateTime.now().minusYears(5)


            Account.insertIgnoreAndGetId {
                it[Account.id] = defaultUser
                it[createdAt] = DateTime.now()
                it[lastSeen] = DateTime.now()
                it[email] = UUID.randomUUID().toString()
                it[givenName] = "Kustutatud"
                it[familyName] = "Kasutaja"
                it[idMigrationDone] = true
                it[isTeacher] = false
                it[isStudent] = false
                it[isAdmin] = false
                it[pseudonym] = defaultUser
            }

            val accountsToDelete: List<String> = Account
                .select(Account.id)
                .where {
                    not(Account.isAdmin) and ( // Admin account is never deleted
                            (Account.isStudent and not(Account.isTeacher)) and (Account.lastSeen lessEq twoYearsAgo) or // Student account 2 years
                                    (Account.isTeacher and (Account.lastSeen lessEq fiveYearsAgo)) // Teacher account 5 years
                            )
                }.map { it[Account.id].value }



            if (accountsToDelete.isEmpty()) {
                log.debug { "No inactive users qualifying for deletion found." }
                return@transaction
            }

            log.info { "Deleting inactive users: $accountsToDelete" }

            // Assign content that are not meant for deletion to default user
            Article.update({ Article.owner inList accountsToDelete }) {
                it[Article.owner] = defaultUser
            }

            ArticleAlias.update({ ArticleAlias.owner inList accountsToDelete }) {
                it[ArticleAlias.owner] = defaultUser
            }

            ArticleVersion.update({ ArticleVersion.author inList accountsToDelete }) {
                it[ArticleVersion.author] = defaultUser
            }

            Exercise.update({ Exercise.owner inList accountsToDelete }) {
                it[Exercise.owner] = defaultUser
            }

            ExerciseVer.update({ ExerciseVer.author inList accountsToDelete }) {
                it[ExerciseVer.author] = defaultUser
            }

            StoredFile.update({ StoredFile.owner inList accountsToDelete }) {
                it[StoredFile.owner] = defaultUser
            }


            // Delete, where order of deletion is not important and no migration is needed
            LogReport.deleteWhere { LogReport.userId inList accountsToDelete }
            SubmissionDraft.deleteWhere { SubmissionDraft.student inList accountsToDelete }
            FeedbackSnippet.deleteWhere { FeedbackSnippet.teacher inList accountsToDelete }
            AutogradeActivity.deleteWhere { AutogradeActivity.student inList accountsToDelete }


            // If student is deleted, delete TeacherActivity
            TeacherActivity.deleteWhere { TeacherActivity.student inList accountsToDelete }

            // If teacher is removed, but student remains, migrate
            TeacherActivity.update({ TeacherActivity.teacher inList accountsToDelete }) {
                it[TeacherActivity.teacher] = defaultUser
            }


            // As now automatic and teacher feedback for submission is removed, delete submission
            Submission.deleteWhere { Submission.student inList accountsToDelete }
            TeacherSubmission.deleteWhere { TeacherSubmission.teacher inList accountsToDelete }

            // Finally, remove accesses
            CourseExerciseExceptionStudent.deleteWhere { CourseExerciseExceptionStudent.student inList accountsToDelete }
            StudentCourseGroup.deleteWhere { StudentCourseGroup.student inList accountsToDelete }

            StudentCourseAccess.deleteWhere { StudentCourseAccess.student inList accountsToDelete }
            TeacherCourseAccess.deleteWhere { TeacherCourseAccess.teacher inList accountsToDelete }

            AccountGroup.deleteWhere { AccountGroup.account inList accountsToDelete }
            Group.deleteWhere { Group.name inList accountsToDelete and Group.isImplicit }
            // Presume that group_dir_access cascades

            // Finally, delete account:
            Account.deleteWhere {
                Account.id inList accountsToDelete
            }
        }
        // TODO: outside transaction, delete from Keycloak
        //  DELETE /admin/realms/{realm}/users/{user-id}
        //  https://www.keycloak.org/docs-api/latest/rest-api/index.html#_users
    }
}
