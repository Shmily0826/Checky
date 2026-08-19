package com.checky.app.data.network

import com.checky.app.domain.model.ConnectionType
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.RiskLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPolicyTest {

    private val meta = ProviderMeta(
        id = "cloudbox",
        displayName = "CloudBox",
        description = "",
        category = "Cloud",
        iconKey = "cloud",
        accentColor = 0xFF0A7BC2,
        isEnabledByDefault = true,
        connectionType = ConnectionType.HTTP_SESSION,
        riskLevel = RiskLevel.MEDIUM,
        credentialType = CredentialType.SESSION_TOKEN,
        allowedHosts = setOf("api.cloudbox.example.com")
    )

    @Test
    fun allowsDeclaredHostOverHttps() {
        assertTrue(HostPolicy.isAllowed(meta, "https://api.cloudbox.example.com/v1/sync"))
    }

    @Test
    fun rejectsUndeclaredHosts() {
        assertFalse(HostPolicy.isAllowed(meta, "https://evil.example.net/steal"))
        assertFalse(HostPolicy.isAllowed(meta, "https://api.gamepass.example.com/x"))
    }

    @Test
    fun rejectsCleartextHttpEvenOnAllowedHost() {
        assertFalse(HostPolicy.isAllowed(meta, "http://api.cloudbox.example.com/v1/sync"))
    }

    @Test
    fun rejectsMalformedUrls() {
        assertFalse(HostPolicy.isAllowed(meta, "not a url"))
        assertFalse(HostPolicy.isAllowed(meta, ""))
    }

    @Test
    fun rejectsProvidersWithoutDeclaredHosts() {
        val noHosts = meta.copy(allowedHosts = emptySet())
        assertFalse(HostPolicy.isAllowed(noHosts, "https://api.cloudbox.example.com/v1"))
    }

    @Test
    fun portIsIgnoredForHostMatching() {
        assertTrue(HostPolicy.isAllowed(meta, "https://api.cloudbox.example.com:8443/v1"))
    }
}
