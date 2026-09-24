package com.checky.app.domain.providers

import com.checky.app.domain.AuthHealth
import com.checky.app.domain.AuthHealthStore
import com.checky.app.domain.CredentialStore
import org.json.JSONObject

/**
 * Copies only a structurally compatible, same-account MiYouShe session into
 * missing provider entries. Each target keeps its own auth health and game
 * account configuration; this is credential continuity, not validation.
 */
internal object MiyousheCredentialSharing {
    private val providerIds = setOf(
        MiyousheProvider.META.id,
        MiyousheZzzProvider.META.id,
        MiyousheCommunityProvider.META.id
    )
    private val gameProviderIds = setOf(
        MiyousheProvider.META.id,
        MiyousheZzzProvider.META.id
    )
    private val accountKeys = listOf(
        "account_id_v2",
        "account_id",
        "ltuid_v2",
        "ltuid",
        "stuid",
        "login_uid"
    )

    /** Returns target provider IDs newly populated by this local-only step. */
    suspend fun reconcile(
        credentialStore: CredentialStore,
        authHealthStore: AuthHealthStore,
        sourceProviderId: String? = null
    ): Set<String> {
        val entries = providerIds.mapNotNull { id ->
            credentialStore.get(id)?.let { stored ->
                val cookie = extractCookie(stored)
                val identity = accountIdentity(cookie)
                if (cookie.isNotBlank() && identity != null) Entry(id, cookie, identity) else null
            }
        }
        val source = if (sourceProviderId != null) {
            entries.firstOrNull { it.providerId == sourceProviderId } ?: return emptySet()
        } else {
            entries.firstOrNull() ?: return emptySet()
        }

        // Multiple local account identities make the target account ambiguous.
        if (entries.any { it.identity != source.identity }) return emptySet()

        val copied = linkedSetOf<String>()
        providerIds.filter { it != source.providerId }.forEach { targetId ->
            if (credentialStore.has(targetId)) return@forEach
            if (!canShareTo(targetId, source.cookie)) return@forEach

            credentialStore.save(targetId, encodeForTarget(targetId, source.cookie))
            // Sharing does not prove the target endpoint is usable.
            authHealthStore.set(targetId, AuthHealth.UNVERIFIED)
            copied += targetId
        }
        return copied
    }

    suspend fun communityNeedsCanonicalQr(credentialStore: CredentialStore): Boolean =
        !credentialStore.has(MiyousheCommunityProvider.META.id) &&
            gameProviderIds.any { credentialStore.has(it) }

    suspend fun isTargetLinkableAfterCanonicalQr(
        credentialStore: CredentialStore,
        targetProviderId: String
    ): Boolean {
        if (targetProviderId !in gameProviderIds) return false
        val source = credentialStore.get(MiyousheCommunityProvider.META.id)
            ?.let(::extractCookie)
            ?: return false
        val target = credentialStore.get(targetProviderId)
            ?.let(::extractCookie)
            ?: return false
        val sourceIdentity = accountIdentity(source)
        return sourceIdentity != null && sourceIdentity == accountIdentity(target) &&
            classifyMiyousheCommunityCookie(source) is MiyousheCommunityCookieBuildResult.Complete &&
            isGameCompatible(target)
    }

    private fun canShareTo(targetProviderId: String, cookie: String): Boolean = when {
        targetProviderId in gameProviderIds -> isGameCompatible(cookie)
        targetProviderId == MiyousheCommunityProvider.META.id ->
            classifyMiyousheCommunityCookie(cookie) is MiyousheCommunityCookieBuildResult.Complete
        else -> false
    }

    private fun isGameCompatible(cookie: String): Boolean {
        val names = cookieNames(cookie)
        val hasLTokenPair = ("ltoken" in names && "ltuid" in names) ||
            ("ltoken_v2" in names && "ltmid_v2" in names)
        val hasCookieTokenAndAccount =
            ("cookie_token" in names || "cookie_token_v2" in names) &&
                accountKeys.any(names::contains)
        return hasLTokenPair || hasCookieTokenAndAccount
    }

    private fun encodeForTarget(targetProviderId: String, cookie: String): String =
        if (targetProviderId == MiyousheCommunityProvider.META.id) {
            cookie
        } else {
            JSONObject().apply {
                put("version", 1)
                put("cookie", cookie)
                // UID and region stay target-owned and must be selected separately.
            }.toString()
        }

    private fun extractCookie(stored: String): String =
        runCatching { JSONObject(stored).optString("cookie").ifBlank { stored } }
            .getOrDefault(stored)

    private fun accountIdentity(cookie: String): String? {
        val pairs = cookiePairs(cookie)
        return accountKeys.firstNotNullOfOrNull { key -> pairs[key] }
    }

    private fun cookieNames(cookie: String): Set<String> = cookiePairs(cookie).keys

    private fun cookiePairs(cookie: String): Map<String, String> = cookie.split(';')
        .mapNotNull { item ->
            val name = item.substringBefore('=').trim()
            val value = item.substringAfter('=', "").trim()
            if (name.isBlank() || value.isBlank()) null else name to value
        }
        .toMap()

    private data class Entry(
        val providerId: String,
        val cookie: String,
        val identity: String
    )
}
