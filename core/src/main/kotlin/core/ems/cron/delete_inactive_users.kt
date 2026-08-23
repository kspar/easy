package core.ems.cron


import com.fasterxml.jackson.annotation.JsonProperty
import core.db.*
import core.util.SendMailService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.*
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import java.time.Duration
import java.util.*
import org.springframework.boot.restclient.RestTemplateBuilder

@Component
class DeleteInactiveUsers(val sendMailService: SendMailService) {
    private val log = KotlinLogging.logger {}

    // Chosen by fair dice roll, guaranteed to be random - do not change
    // `internal` so DeleteInactiveUsersTest can assert that content changed hands to this account
    // rather than restating the literal and quietly disagreeing with it later.
    internal val defaultUser = "58060066-c4f9-4054-88be-c8492bb8e487"

    @Value("\${easy.core.keycloak.base-url}")
    private lateinit var keycloakBaseUrl: String

    @Value("\${easy.core.keycloak.realm}")
    private lateinit var realm: String

    @Value("\${easy.core.keycloak.client-id}")
    private lateinit var clientId: String

    @Value("\${easy.core.keycloak.client-secret}")
    private lateinit var clientSecret: String

    @Value("\${easy.core.keycloak.ignore-missing-keycloak-users}")
    private var ignoreMissingKeycloakUsers: Boolean = false

    private data class TokenResponse(
        @param:JsonProperty("access_token") val accessToken: String,
    )

    private data class KeycloakUser(
        @param:JsonProperty("id") val id: String,
    )

    @Scheduled(cron = "\${easy.core.keycloak.cron}")
    fun cron() {
        val deletedAccounts = try {
            deleteInactiveAccountsFromDb()
        } catch (e: Exception) {
            // The database stage is a single transaction covering the whole batch, so any failure in
            // it deletes nothing — not the account that caused it, and not the others. Without this
            // catch that outcome is a stack trace in a nightly job, while the notification below only
            // ever reports Keycloak problems, so a retention policy that had stopped running entirely
            // would look exactly like one with nothing to do. Two non-cascading foreign keys put it
            // in that state once already.
            log.error(e) { "Deleting inactive users from the database failed; nothing was deleted" }
            sendMailService.sendSystemNotification(
                """
                    Deleting inactive users failed and the whole batch was rolled back. No account was
                    removed from the database, and none was removed from Keycloak.

                    ${e.message}
                """.trimIndent()
            )
            return
        }

        deleteFromKeycloak(deletedAccounts)
    }

    /**
     * The database half, separated from the Keycloak half so that it can be tested.
     *
     * Not because the split is elegant — the two halves are one operation and the Keycloak one still
     * has to follow — but because everything that can go wrong here goes wrong as a rolled-back
     * transaction, and reproducing that needs a real schema and no Keycloak. See
     * `DeleteInactiveUsersTest`.
     */
    internal fun deleteInactiveAccountsFromDb(): List<String> {
        return transaction {
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
                return@transaction emptyList()
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
            // Their bug reports go with them. The YouTrack issues these produced do not — those are
            // ours, they are already restricted to the team, and an issue that loses its reporter is
            // still a bug worth fixing.
            BugReport.deleteWhere { BugReport.userId inList accountsToDelete }
            SubmissionDraft.deleteWhere { SubmissionDraft.student inList accountsToDelete }
            FeedbackSnippet.deleteWhere { FeedbackSnippet.teacher inList accountsToDelete }
            AutogradeActivity.deleteWhere { AutogradeActivity.student inList accountsToDelete }


            // If student is deleted, delete TeacherActivity
            TeacherActivity.deleteWhere { TeacherActivity.student inList accountsToDelete }

            // If teacher is removed, but student remains, migrate
            TeacherActivity.update({ TeacherActivity.teacher inList accountsToDelete }) {
                it[TeacherActivity.teacher] = defaultUser
            }


            // Inline comments are the same kind of artefact as TeacherActivity above — a teacher's
            // feedback on one submission — so they follow the same two rules: they go when the
            // student goes, and they change hands when only their author does.
            //
            // This block has to exist and has to be here. `teacher_inline_comment` has three foreign
            // keys and **none of them cascades** (changesets/v4.xml), so `submission_id` blocks the
            // `Submission.deleteWhere` immediately below and `teacher_id` blocks the
            // `Account.deleteWhere` at the end. The table arrived in v4, after this cron was written,
            // and the whole cron is one transaction — so the omission did not lose inline comments,
            // it stopped the retention policy running at all.
            TeacherInlineComment.deleteWhere {
                TeacherInlineComment.submission inSubQuery
                        Submission.select(Submission.id)
                            .where { Submission.student inList accountsToDelete }
            }
            TeacherInlineComment.update({ TeacherInlineComment.teacher inList accountsToDelete }) {
                it[TeacherInlineComment.teacher] = defaultUser
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
            // The directory grants held by that group go with it, and this is not a presumption:
            // `fk_group_exercise_dir_access_group` was dropped and re-added `onDelete="CASCADE"` in
            // changeset `160525-1`. Worth citing rather than assuming, because the grant is not rare —
            // `CreateExercise` and `CreateDir` both give the author's implicit group `PRAWM`, so
            // anyone who has ever made a library exercise has one, and if it did not cascade this one
            // line would roll back the whole batch every night. `AccountGroup` above is a different
            // table: it clears who is *in* the group, not what the group can reach.

            // Finally, delete account:
            Account.deleteWhere {
                Account.id inList accountsToDelete
            }

            accountsToDelete
        }
    }

    private fun deleteFromKeycloak(deletedAccounts: List<String>) {
        val token = getAccessToken()

        val failedUsers = mutableListOf<Pair<String, String>>()
        deletedAccounts.forEach {
            try {
                val keycloakUserId = getKeycloakUserId(it, token)
                if (keycloakUserId == null) {
                    if (!ignoreMissingKeycloakUsers) {
                        log.error { "Cannot find Keycloak user '$it'" }
                        failedUsers.add(it to "Could not find inactive Keycloak user")
                    }
                } else {
                    log.info { "Deleting Keycloak user '$it' ($keycloakUserId)" }
                    val deleted = deleteKeycloakUser(keycloakUserId, token)
                    if (!deleted) {
                        log.error { "Cannot delete Keycloak user '$it'" }
                        failedUsers.add(it to "Failed to delete inactive Keycloak user")
                    }
                }
            } catch (e: Exception) {
                log.error { "Deleting inactive Keycloak user failed with exception: ${e.message}" }
                failedUsers.add(it to e.message.orEmpty())
            }
        }

        if (failedUsers.isNotEmpty())
            sendMailService.sendSystemNotification(
                """
                    Failed to delete inactive Keycloak users:
                    
                    ${failedUsers.joinToString("\n") { it.first + " - " + it.second }}
                """.trimIndent()
            )

        log.info { "Deleted Keycloak users" }
    }

    private fun getAccessToken(): String {
        // `$realm`, not a hardcoded "master". The service account belongs to the client, the client
        // belongs to a realm, and its token comes from that realm's token endpoint — so a hardcoded
        // realm here and a configured one in the two admin calls below silently disagree the moment
        // anyone configures a realm that is not master. That is only invisible today because both
        // environments happen to use master, which makes it the kind of bug that surfaces during
        // the migration rather than before it.
        val tokenUrl = "$keycloakBaseUrl/auth/realms/$realm/protocol/openid-connect/token"

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
        }

        val body = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", clientId)
            add("client_secret", clientSecret)
        }

        val request = HttpEntity(body, headers)
        val response = restTemplate().postForObject(tokenUrl, request, TokenResponse::class.java)

        return response?.accessToken ?: throw RuntimeException("Failed to get access token")
    }

    private fun getKeycloakUserId(username: String, accessToken: String): String? {
        val searchUrl = "$keycloakBaseUrl/auth/admin/realms/$realm/users"

        val headers = HttpHeaders().apply {
            setBearerAuth(accessToken)
        }

        val uriVariables = mapOf(
            "username" to username,
            "exact" to "true"
        )

        val responseType = object : ParameterizedTypeReference<List<KeycloakUser>>() {}

        val response = restTemplate().exchange(
            "$searchUrl?username={username}&exact={exact}",
            HttpMethod.GET,
            HttpEntity<Any>(headers),
            responseType,
            uriVariables
        )

        val body = response.body

        if (body == null) {
            log.error { "Keycloak user search returned null" }
            return null
        }

        if (body.isEmpty()) {
            if (!ignoreMissingKeycloakUsers)
                log.error { "Keycloak user not found by username '$username'" }
            return null
        }

        val id = body.singleOrNull()?.id

        if (id == null) {
            log.error { "Multiple Keycloak users found with username '$username': $body" }
            return null
        }

        return id
    }

    private fun deleteKeycloakUser(userId: String, accessToken: String): Boolean {
        val deleteUrl = "$keycloakBaseUrl/auth/admin/realms/$realm/users/$userId"

        val headers = HttpHeaders().apply {
            setBearerAuth(accessToken)
        }

        val response = restTemplate().exchange(
            deleteUrl,
            HttpMethod.DELETE,
            HttpEntity<Any>(headers),
            Void::class.java
        )

        return response.statusCode == HttpStatus.NO_CONTENT
    }

    private fun restTemplate() = RestTemplateBuilder()
        .connectTimeout(Duration.ofSeconds(60))
        .readTimeout(Duration.ofSeconds(60))
        .build()
}
