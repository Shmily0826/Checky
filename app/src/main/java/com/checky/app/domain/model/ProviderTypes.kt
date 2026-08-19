package com.checky.app.domain.model

/**
 * How a provider connects to its service.
 */
enum class ConnectionType {
    /** Official API / OAuth — the safest integration type. */
    OFFICIAL_API,

    /** User-supplied session credential used against an HTTP API. */
    HTTP_SESSION,

    /** UI automation / assisted flow — NOT implemented in this MVP. */
    UI_ASSISTED
}

/**
 * Risk level shown to the user before connecting a service.
 */
enum class RiskLevel {
    /** Official API or OAuth. */
    LOW,

    /** User-provided session credential (may break when the platform changes). */
    MEDIUM,

    /** UI automation or unstable integration — shown as "not supported yet". */
    HIGH
}

/**
 * What kind of credential a provider needs.
 */
enum class CredentialType {
    NONE,
    OAUTH,
    SESSION_TOKEN
}

/**
 * Whether this provider can be connected in the current build.
 */
enum class SupportStatus {
    SUPPORTED,
    NOT_SUPPORTED_YET
}
