package com.checky.app.data.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactingLoggingInterceptorTest {

    private val interceptor = RedactingLoggingInterceptor()

    @Test
    fun redactedUrlMasksSensitiveQueryParameters() {
        val url = "https://api.cloudbox.example.com/v1/sync?access_token=SECRET123&page=2".toHttpUrl()
        val safe = interceptor.redactedUrl(url)

        assertFalse(safe.contains("SECRET123"))
        assertTrue(safe.contains("access_token=<redacted>"))
        // Non-sensitive parameters are preserved.
        assertTrue(safe.contains("page=2"))
    }

    @Test
    fun redactedUrlMasksTokensAndApiKeys() {
        for (name in listOf("refresh_token", "session", "api_key", "apikey", "code", "secret")) {
            val url = "https://host.example.com/x?$name=LEAKED_VALUE&keep=1".toHttpUrl()
            val safe = interceptor.redactedUrl(url)
            assertFalse("$name leaked its value", safe.contains("LEAKED_VALUE"))
            assertTrue(safe.contains("$name=<redacted>"))
        }
    }

    @Test
    fun headerListCoversSensitiveHeaders() {
        val expected = setOf(
            "Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization",
            "X-Api-Key", "X-Auth-Token", "X-Session-Token"
        )
        expected.forEach { header ->
            assertTrue(
                "$header must be treated as sensitive",
                RedactingLoggingInterceptor.SENSITIVE_HEADERS.any { it.equals(header, ignoreCase = true) }
            )
        }
    }

    @Test
    fun redactBodyStripsTokenPatterns() {
        val body = "error=invalid&access_token=abc123&refresh_token=def456 cookie=jwt-token"
        val safe = interceptor.redactBody(body)

        assertFalse(safe.contains("abc123"))
        assertFalse(safe.contains("def456"))
        assertFalse(safe.contains("jwt-token"))
    }

    @Test
    fun redactBodyLeavesSafeTextAlone() {
        val body = "{\"status\":\"ok\",\"points\":20}"
        assertEquals(body, interceptor.redactBody(body))
    }
}
