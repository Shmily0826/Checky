package com.checky.app.data.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.nio.charset.StandardCharsets

/**
 * OkHttp interceptor that logs requests/responses while redacting sensitive
 * values. Never logs: Authorization, Cookie, Set-Cookie, access/refresh
 * tokens, session ids or API keys — and (in release builds) no bodies at all.
 */
class RedactingLoggingInterceptor(
    private val logBody: Boolean = true
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val safeUrl = redactedUrl(request.url)
        log("→ ${request.method} $safeUrl")
        request.headers.names()
            .filter { it.isSensitiveHeader() }
            .forEach { log("→ header $it: <redacted>") }

        var response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            log("← ${e.javaClass.simpleName}: ${e.message?.let { redactBody(it) }}")
            throw e
        }

        log("← ${response.code} ${response.message} $safeUrl")
        response.headers.names()
            .filter { it.isSensitiveHeader() }
            .forEach { log("← header $it: <redacted>") }
        return response
    }

    /** Redacted URL — query parameters with sensitive names are masked. */
    fun redactedUrl(url: HttpUrl): String {
        val safeQuery = url.queryParameterNames
            .filter { it.isSensitiveQueryParam() }
            .joinToString("&") { "$it=<redacted>" }
        val publicQuery = url.queryParameterNames
            .filterNot { it.isSensitiveQueryParam() }
            .joinToString("&") { "$it=${url.queryParameter(it)}" }
        val query = listOf(safeQuery, publicQuery).filter { it.isNotEmpty() }.joinToString("&")
        return if (query.isEmpty()) "${url.scheme}://${url.host}${url.encodedPath}"
        else "${url.scheme}://${url.host}${url.encodedPath}?$query"
    }

    /** Redact well-known token patterns from free text (e.g. error messages). */
    fun redactBody(body: String): String {
        var out = body
        for (pattern in TOKEN_PATTERNS) {
            out = out.replace(pattern, "<redacted>")
        }
        return out
    }

    private fun log(message: String) {
        // Intentionally never contains secrets; plain println is fine for a mock.
        println("CheckyNet $message")
    }

    private fun String.isSensitiveHeader(): Boolean =
        SENSITIVE_HEADERS.any { equals(it, ignoreCase = true) }

    private fun String.isSensitiveQueryParam(): Boolean =
        SENSITIVE_QUERY_PARAMS.any { equals(it, ignoreCase = true) }

    companion object {
        val SENSITIVE_HEADERS = setOf(
            "Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization",
            "X-Api-Key", "X-API-Key", "X-Auth-Token", "X-Session-Token"
        )

        val SENSITIVE_QUERY_PARAMS = setOf(
            "access_token", "refresh_token", "token", "session", "api_key", "apikey", "key", "auth", "code", "secret"
        )

        val TOKEN_PATTERNS = listOf(
            Regex("""(?i)(access_token|refresh_token|api[_-]?key|session[_-]?id|authorization|cookie)[=:]["']?[^&\s"'},]+""")
        )
    }
}
