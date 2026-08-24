package core.conf.security

import core.testing.HttpApi
import core.testing.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.test.web.servlet.MockMvc

/**
 * That Spring's `StrictHttpFirewall` is doing its default job on header values.
 *
 * There was a bean here turning that off — `setAllowedHeaderValues { true }`, for every request in
 * every environment, labelled a temporary workaround for EZ-1434 ("auth fails if the user's name
 * contains Ü") from when mod_auth_openidc passed claims as HTTP headers. Nothing in front of core
 * does that any more and EZ-1434 is closed, so the bean is gone and this is what stops it coming
 * back by accident.
 *
 * **Why the assertion is 400-versus-401 rather than a bean lookup.** With no override, the firewall
 * is created inside `FilterChainProxy` rather than published as a bean, so there is nothing to
 * autowire and assert on. Status is the honest test anyway: it goes through the real chain, and the
 * two outcomes are cleanly attributable — 400 is the firewall refusing the request, 401 is the
 * application receiving it and declining the credential.
 *
 * **And why the header is `Authorization`.** Validation is lazy: `StrictFirewalledRequest` checks a
 * value inside `getHeader`, not up front, so a header nothing reads is never validated and a test
 * using an invented header name would pass with the firewall switched off. `Authorization` is read
 * on every request by the bearer-token filter.
 *
 * Values are written as `\u` escapes rather than literal characters, so that what is being sent stays
 * legible in a diff and cannot be mangled by an editor.
 */
@IntegrationTest
class FilterChainProxyFirewallTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val context: ApplicationContext,
) {

    private val api = HttpApi(mockMvc)

    // `getWithHeaders`, not the `caller` slot: that parameter means identity, and passing null through
    // it clears the security context — so borrowing it for a header would have made these tests pass
    // or fail for a reason unrelated to the header under test.
    private fun getWithAuthorization(value: String) =
        api.getWithHeaders("/v2/versions", mapOf("Authorization" to value))

    @Test
    fun `a control character in a header value is refused before the application sees it`() {
        // The actual shape of EZ-1434 rather than a synthetic payload: `Ü` is 0xC3 0x9C in UTF-8,
        // request headers are decoded as ISO-8859-1, so a name typed into curl arrives as `Ã`
        // (U+00C3) followed by U+009C — a C1 control character. The firewall refuses the request,
        // and the 400 says nothing about names, which is why this read as a bug about `Ü`.
        val resp = getWithAuthorization("Bearer \u00C3\u009C")
        assertEquals(400, resp.status) { "expected the firewall to refuse this: ${resp.body}" }
    }

    @Test
    fun `an ordinary bad credential still reaches the application and gets 401`() {
        // The control. Without it, a chain that answered 400 to everything would pass the test above.
        val resp = getWithAuthorization("Bearer not-a-token")
        assertEquals(401, resp.status) { resp.body }
    }

    @Test
    fun `core has no username-and-password authentication at all`() {
        // Boot's UserDetailsServiceAutoConfiguration is excluded in EasyCoreApp. It had been
        // suppressed only incidentally, by an `authenticationManager` bean and an
        // `EasyUserAuthProvider` that nothing invoked — so deleting those for being dead would have
        // switched it on, creating an InMemoryUserDetailsManager and logging a generated password at
        // startup. Identity here comes from a JWT or, in dev, from oidc_claim_* headers; there is no
        // third source, and this asserts that rather than trusting a bean's continued existence.
        assertEquals(0, context.getBeanNamesForType(UserDetailsService::class.java).size) {
            context.getBeanNamesForType(UserDetailsService::class.java).joinToString()
        }
        assertEquals(0, context.getBeanNamesForType(AuthenticationProvider::class.java).size) {
            context.getBeanNamesForType(AuthenticationProvider::class.java).joinToString()
        }
    }

    @Test
    fun `a non-ASCII character is not itself the problem`() {
        // Worth pinning, because "the firewall rejects non-ASCII" is the natural misreading of
        // EZ-1434 and is what would justify bringing the workaround back. The default pattern is
        // `[\p{IsAssigned}&&[^\p{IsControl}]]*`, so `Ü` passes on its own and only the mojibake
        // control character fails. 401 and not 400: the value reached the application.
        val resp = getWithAuthorization("Bearer \u00DClo")
        // 401 from DefaultBearerTokenResolver's token regex rather than from JWT decoding — a
        // different mechanism from the `not-a-token` control above, but the same distinction that
        // matters here: the value reached the application instead of being refused at the door.
        assertEquals(401, resp.status) { "a bare non-ASCII letter should not be refused: ${resp.body}" }
    }
}
