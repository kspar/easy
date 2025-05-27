package core.ems.cron


import com.fasterxml.jackson.annotation.JsonProperty
import core.db.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.*
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import java.util.*


@Component
class DeleteInactiveUsers {
    private val log = KotlinLogging.logger {}

    // Chosen by fair dice roll, guaranteed to be random - do not change
    private val defaultUser = "58060066-c4f9-4054-88be-c8492bb8e487"

    @Value("\${easy.core.keycloak.base-url}")
    private lateinit var keycloakBaseUrl: String

    @Value("\${easy.core.keycloak.realm}")
    private lateinit var realm: String

    @Value("\${easy.core.keycloak.client-id}")
    private lateinit var clientId: String

    @Value("\${easy.core.keycloak.client-secret}")
    private lateinit var clientSecret: String

    private data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
    )

    private data class KeycloakUser(
        @JsonProperty("id") val id: String,
    )

    // FIXME: for testing only
    @Scheduled(cron = "*/30 * * * * *")
    fun testingCron() {
        val deletedAccounts = transaction {
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
            accountsToDelete
        }

        // Delete from Keycloak
        val token = getAccessToken()

        deletedAccounts.forEach {
            val keycloakUserId = getKeycloakUserId(it, token)
            log.info { "Deleting Keycloak user '$it' ($keycloakUserId)" }
            if (keycloakUserId == null) {
                log.error { "Cannot delete Keycloak user '$it'" }
            } else {
                //deleteKeycloakUser(keycloakUserId, token)
            }
        }
        log.info { "Deleted Keycloak users" }
    }

            // TODO: once a day, for now every 30 seconds for testing
    //@Scheduled(cron = "*/30 * * * * *")
    fun cron() {
        val deletedAccounts = transaction {
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

            accountsToDelete
        }

        // Delete from Keycloak
        val token = getAccessToken()

        deletedAccounts.forEach {
            val keycloakUserId = getKeycloakUserId(it, token)
            log.info { "Deleting Keycloak user '$it' ($keycloakUserId)" }
            if (keycloakUserId == null) {
                log.error { "Cannot delete Keycloak user '$it'" }
            } else {
                //deleteKeycloakUser(keycloakUserId, token)
            }
        }
        log.info { "Deleted Keycloak users" }
    }

    private fun getAccessToken(): String {
        val tokenUrl = "$keycloakBaseUrl/auth/realms/master/protocol/openid-connect/token"

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
        }

        val body = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "client_credentials")
            add("client_id", clientId)
            add("client_secret", clientSecret)
        }

        val request = HttpEntity(body, headers)
        val response = RestTemplate().postForObject(tokenUrl, request, TokenResponse::class.java)

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

        val response = RestTemplate().exchange(
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

        val response = RestTemplate().exchange(
            deleteUrl,
            HttpMethod.DELETE,
            HttpEntity<Any>(headers),
            Void::class.java
        )

        return response.statusCode == HttpStatus.NO_CONTENT
    }
}
