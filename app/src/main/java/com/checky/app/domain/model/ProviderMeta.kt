package com.checky.app.domain.model

import java.time.ZoneId

/**
 * Static, code-defined metadata for a check-in provider.
 * Real providers added later just supply their own [ProviderMeta].
 */
data class ProviderMeta(
    val id: String,
    val displayName: String,
    val description: String,
    val category: String,
    /** Key used by the UI to pick an icon / emoji. No network, no credentials. */
    val iconKey: String,
    /** Accent color used for the card (ARGB long). */
    val accentColor: Long,
    val isEnabledByDefault: Boolean,
    /** How the provider connects. */
    val connectionType: ConnectionType = ConnectionType.OFFICIAL_API,
    /** Risk level shown to the user before connecting. */
    val riskLevel: RiskLevel = RiskLevel.LOW,
    /** What kind of credential this provider needs. */
    val credentialType: CredentialType = CredentialType.NONE,
    /** Whether this provider can be connected in this build. */
    val supportStatus: SupportStatus = SupportStatus.SUPPORTED,
    /** Hosts this provider is allowed to contact. Empty for mock providers. */
    val allowedHosts: Set<String> = emptySet(),
    /** Time zone whose calendar date defines this provider's daily business day. */
    val businessZone: ZoneId = ZoneId.of("UTC")
)
