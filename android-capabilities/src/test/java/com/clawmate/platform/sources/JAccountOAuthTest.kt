package com.clawmate.platform.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class JAccountOAuthTest {
    @Test fun redirectUriMustBeHttpsAndRegistered() {
        assertNull(JAccountOAuth.validateRedirectUri("https://myapp.sjtu.edu.cn/jaccount/callback"))
        assertNotNull(JAccountOAuth.validateRedirectUri(""))
        assertNull(JAccountOAuth.validateRedirectUri(JAccountOAuth.DEEP_LINK_REDIRECT))
        assertNotNull(JAccountOAuth.validateRedirectUri("http://myapp.sjtu.edu.cn/cb"))
        assertNotNull(JAccountOAuth.validateRedirectUri("https://bad host/cb"))
    }

    @Test fun accountNormalizationAcceptsJAccountNames() {
        assertEquals("abc@sjtu.edu.cn", JAccountOAuth.normalizeAccount("abc"))
        assertEquals("abc@sjtu.edu.cn", JAccountOAuth.normalizeAccount(" ABC@SJTU.edu.cn "))
        assertNull(JAccountOAuth.normalizeAccount("abc@example.com"))
        assertNull(JAccountOAuth.normalizeAccount(""))
        assertNull(JAccountOAuth.normalizeAccount("../etc"))
        assertNull(JAccountOAuth.normalizeAccount("a b@sjtu.edu.cn"))
    }

    @Test fun authorizeUrlCarriesRequiredParameters() {
        val url = JAccountOAuth.authorizeUrl("client123", "https://app.sjtu.edu.cn/cb", "0123456789abcdef01234567")
        assertTrue(url.startsWith("https://jaccount.sjtu.edu.cn/oauth2/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client123"))
        assertTrue(url.contains("scope=openid"))
        assertTrue(url.contains("state=0123456789abcdef01234567"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapp.sjtu.edu.cn%2Fcb"))
    }

    @Test fun authorizeUrlRejectsBadState() {
        val failure = runCatching { JAccountOAuth.authorizeUrl("client123", "https://app.sjtu.edu.cn/cb", "short") }
        assertTrue(failure.isFailure)
    }

    @Test fun callbackValidatesStateAndErrors() {
        val ok = JAccountOAuth.parseCallback("code=abc123&state=s1", "s1")
        assertEquals("abc123", ok.getOrNull())

        val mismatched = JAccountOAuth.parseCallback("code=abc123&state=other", "s1")
        assertTrue(mismatched.isFailure)
        assertTrue(mismatched.exceptionOrNull()!!.message!!.contains("状态不匹配"))

        val denied = JAccountOAuth.parseCallback("error=access_denied&state=s1", "s1")
        assertTrue(denied.isFailure)
        assertTrue(denied.exceptionOrNull()!!.message!!.contains("拒绝"))

        assertTrue(JAccountOAuth.parseCallback("state=s1", "s1").isFailure)
    }

    @Test fun deepLinkQueryIsExtracted() {
        val link = "clawmate://jaccount?code=xyz&state=abc"
        assertTrue(JAccountCallback.isOwnLink(link))
        assertEquals("code=xyz&state=abc", JAccountCallback.queryOf(link))
        assertFalse(JAccountCallback.isOwnLink("https://evil.example/?code=xyz"))
        assertEquals("code=xyz", JAccountCallback.queryOf("https://app.sjtu.edu.cn/cb?code=xyz#fragment"))
    }

    @Test fun tokenPayloadNeverReturnsEmptySecret() {
        val failure = runCatching { JAccountOAuth.tokenPayload("code", "client", "", "https://app.sjtu.edu.cn/cb") }
        assertTrue(failure.isFailure)
        val payload = JAccountOAuth.tokenPayload("code", "client", "secret123", "https://app.sjtu.edu.cn/cb")
        assertEquals("authorization_code", payload["grant_type"])
        assertEquals("secret123", payload["client_secret"])
    }

    @Test fun bearerPayloadFollowsRfc7628() {
        val raw = String(Base64.getDecoder().decode(ImapTransport.bearerPayload("abc@sjtu.edu.cn", "tok123", "mail.sjtu.edu.cn", 993)), Charsets.UTF_8)
        assertEquals("n,a=abc@sjtu.edu.cn,\u0001host=mail.sjtu.edu.cn\u0001port=993\u0001auth=Bearer tok123\u0001\u0001", raw)
    }

    @Test fun accessTokenMustBeUsable() {
        assertEquals("abcdefgh", JAccountOAuth.accessToken(mapOf("access_token" to "abcdefgh")))
        assertNull(JAccountOAuth.accessToken(mapOf("access_token" to "short")))
        assertNull(JAccountOAuth.accessToken(mapOf("access_token" to "abc defghij")))
        assertNull(JAccountOAuth.accessToken(emptyMap()))
    }
}
