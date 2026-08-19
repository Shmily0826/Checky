package com.checky.app.data.network

import com.checky.app.domain.model.ProviderMeta
import java.net.URL

/**
 * Enforces per-provider network boundaries:
 * - a provider may only talk to its declared [ProviderMeta.allowedHosts]
 * - HTTP (cleartext) is never allowed — HTTPS only
 */
object HostPolicy {

    /**
     * True only when [url] uses HTTPS and its host is in the provider's
     * allowlist. Anything else is rejected (a real HTTP client would wrap
     * this around its request dispatch).
     */
    fun isAllowed(meta: ProviderMeta, url: String): Boolean {
        if (meta.allowedHosts.isEmpty()) return false
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        if (!parsed.protocol.equals("https", ignoreCase = true)) return false
        return meta.allowedHosts.any { it.equals(parsed.host, ignoreCase = true) }
    }
}
