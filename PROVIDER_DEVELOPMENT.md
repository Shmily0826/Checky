# PROVIDER_DEVELOPMENT.md — adding a real provider to Checky

The provider abstraction isolates every external service. A real provider plugs
in without touching UI or orchestration — and **never exposes credentials**.

## 1. The contract you implement

```kotlin
interface CheckInProvider {
    val meta: ProviderMeta                    // id, name, icon, category, accent,
                                              // connectionType, riskLevel,
                                              // credentialType, supportStatus,
                                              // allowedHosts (!!)
    val requiresCredentials: Boolean          // defaults from credentialType
    fun checkIn(): Flow<CheckInEvent>         // stream Progress, end with Done(result)
    suspend fun validateCredentials(secret: String): CredentialValidation
    fun supportsDisconnect(): Boolean
    suspend fun disconnect()
}
```

A check-in **must** end with exactly one `CheckInEvent.Done(CheckInResult)`
whose `CheckInResult.outcome` is one of:

| Outcome | Meaning |
|---|---|
| `Success` | Reward earned |
| `AlreadyCompleted` | Already checked in today |
| `AuthenticationExpired` | Credential/session invalid → recommend reconnect |
| `ActionRequired` | User must do something (e.g. open the service) |
| `TemporaryFailure` | Retry later (timeout / rate limit / maintenance) |
| `PermanentFailure` | Do not retry |
| `Unsupported` | Not available in this build |

Outcomes carry **only** safe data: `userMessage`, `diagnosticCode`,
`reward`, `retryRecommendation`. Never put headers, cookies, tokens, raw
bodies, stack traces, or PII in an outcome.

## 2. Step-by-step

1. **Declare the metadata** — connection type, risk level, credential type,
   and the **host allowlist**:
   ```kotlin
   override val meta = ProviderMeta(
       id = "steam", displayName = "Steam Daily", ...,
       connectionType = ConnectionType.HTTP_SESSION,
       riskLevel = RiskLevel.MEDIUM,
       credentialType = CredentialType.SESSION_TOKEN,
       allowedHosts = setOf("api.example.com")
   )
   ```
   > `allowedHosts` is enforced by `HostPolicy`: any request to another host,
   > or over plain HTTP, is refused.
2. **Read credentials through the vault only** — never store them yourself:
   ```kotlin
   val token = credentialStore.get(meta.id)   // inject CredentialStore
   ```
3. **Call the API inside `checkIn()`** using your Retrofit/OkHttp client
   **with `RedactingLoggingInterceptor` attached**. Use the user-facing error
   mapping for HTTP failures:
   ```kotlin
   401 -> AuthenticationExpired("Your connection has expired. Reconnect this service.")
   timeout -> TemporaryFailure("The service did not respond. Try again later.")
   429 -> TemporaryFailure("This service temporarily limited requests.", "RATE_LIMIT")
   503 -> TemporaryFailure("This service may be under maintenance.", "MAINTENANCE")
   ```
4. **Emit** `Progress(...)` updates, then `Done(CheckInResult(...))`.
5. **Register** in `di/ProviderModule`:
   - add the provider instance to `provideProviders(...)`;
   - add its `ProviderMeta` to `provideProviderCatalog()`.
6. **Handle connect/disconnect** — `validateCredentials()` checks a candidate
   before it is saved; `disconnect()` clears provider-side state.

## 3. Credential rules (mandatory)

- Use the `CredentialStore` (per-provider keys; the real impl is Keystore AES/GCM).
- Never log the secret, never put it in a URL, never store it in the provider.
- The connect screen already: hides secrets, validates, sets FLAG_SECURE,
  offers per-provider delete, and supports "Delete all credentials".
- Do not add secrets to `UserPreferences`/DataStore — those are non-sensitive.

## 4. Compliance checklist — answer every question before shipping a provider

- [ ] **Does the platform offer an official API or OAuth?** Prefer it over session scraping.
- [ ] **Does automation comply with the platform's terms of service?**
- [ ] **Is the operation limited to check-in?** No purchases, redemptions, lotteries, posting, messaging.
- [ ] **Is the action idempotent?** Running it twice must not double-reward.
- [ ] **Can credentials be refreshed safely?** Never store refresh tokens insecurely.
- [ ] **Are all hosts declared** in `allowedHosts` and HTTPS-only?
- [ ] **Are sensitive fields redacted** in every log path (`RedactingLoggingInterceptor`)?
- [ ] **Are success and failure responses tested** (unit + instrumented)?
- [ ] **Does the provider avoid CAPTCHA bypass and platform-protection evasion?**
- [ ] **Can the user disconnect and delete credentials?** (per-provider delete + delete-all)

## 5. Test template

```kotlin
@Test
fun providerMapsApiFailureToUserFacingOutcome() = runTest {
    val api = FakeApi().apply { next = Result.failure(HttpException(401)) }
    val done = MyProvider(api, credentialStore).checkIn()
        .filterIsInstance<CheckInEvent.Done>().single()
    assertEquals(CheckInStatus.LOGIN_EXPIRED, done.result.status)
    assertTrue(done.result.message.contains("Reconnect"))
    assertFalse(done.result.message.contains("401"))   // no raw codes/stack traces
}
```

## 6. What stays out of scope

- ❌ UI automation providers (`ConnectionType.UI_ASSISTED`) — shown as "Not supported yet".
- ❌ Anything requiring AccessibilityService, root, or cross-app control.
