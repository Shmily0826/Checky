# ARCHITECTURE.md — Checky

Local-first Android MVP. Kotlin, Jetpack Compose, Material 3, MVVM, Room,
DataStore, Coroutines/Flow, Hilt.

## Product scope and prioritization

Checky is optimized first for one owner's accounts on one Xiaomi/HyperOS
device. A Xiaomi/HyperOS-specific implementation or instruction is preferred
when it materially lowers friction on that device; generic OEM abstractions and
cross-device compatibility are optional, not architectural requirements.
Explicitly user-granted device permissions, including background-popup,
overlay, or accessibility permissions, may be considered when their benefit is
concrete. Each must be narrowly scoped, clearly documented, easy to revoke,
and never used to bypass provider security, verification, CAPTCHA, or risk
controls. Device-specific behavior is not evidence of general support and
must retain separate implemented/build-tested/device-tested/live-verified
labels.

## Layered diagram

```
┌─────────────────────────────────────────────────────────────┐
│  UI (Compose, Material 3)                                   │
│  Onboarding · Home · Add services · Connect · History ·     │
│  Settings · Provider details                                │
│  ViewModels (StateFlow, viewModelScope, Hilt-injected)      │
├─────────────────────────────────────────────────────────────┤
│  Domain                                                      │
│  Six concrete providers: HoYoverse Genshin, miyoushe       │
│  community, HoYoverse ZZZ, Taygedo NTE, Taygedo            │
│  community, TapTap game-sign                               │
│  CheckInProvider contract + opt-in QR/SMS/game-role caps    │
│  CheckInEvent (Progress / Done)                             │
│  CheckInOutcome (Success/Already/AuthExpired/ActionRequired │
│                  /TemporaryFailure/PermanentFailure/Unsup.) │
│  CheckInAllUseCase (orchestrator: concurrency, guard,       │
│                  continue-on-failure, cancellation, summary)│
│  CredentialStore (per-provider vault contract)              │
├─────────────────────────────────────────────────────────────┤
│  Data                                                        │
│  Room: check_in_records (history) + services (state)        │
│  DataStore: user prefs (theme, reminder, run mode, auto-run)│
│  Security: KeystoreCredentialStore (default) ⇄ MockCredentialStore (tests)│
│  Network (prepared): HostPolicy allowlist,                  │
│                      RedactingLoggingInterceptor            │
│  Repository (single source for UI)                          │
└─────────────────────────────────────────────────────────────┘
```

## Key flows

### "Check in all" (orchestration)

1. `HomeViewModel.checkInAll()` filters providers to those **selected** in Room
   and currently **connected/executable**. Credential-required providers must
   have a stored credential; SMS providers must report a local session; a
   credential-free provider may run without a credential.
2. It reads the user's **run mode**: `PARALLEL` (semaphore-limited to 3) or `SEQUENTIAL`.
3. `CheckInAllUseCase.invoke(providers, parallel)`:
   - sets an `AtomicBoolean` guard → duplicate triggers are ignored;
   - streams `CheckInAllProgress.Running(states)` every 40 ms while any job is alive;
   - each provider's `Flow<CheckInEvent>` is collected: `Progress` patches live UI state; `Done` persists the safe `CheckInResult` to Room;
   - a provider throwing is caught (except `CancellationException`) and mapped to a safe `TemporaryFailure` — **other providers continue**;
   - cancellation propagates cleanly and persists nothing;
   - finishes with one `CheckInAllProgress.Finished(states, summary)` (points/XP/membership days/durations).
4. Home renders live progress per service card and a final summary banner; individual **Retry** (FAILED) and **Reconnect → Connect screen** (LOGIN_EXPIRED) actions are available per card.

The opt-in `AutoCheckInWorker`, including the home-screen widget entry point,
uses the same connection gate before invoking the use case. A catalog default or
an enabled Room row alone is never sufficient to execute a credential-required
provider. Its daily WorkManager request is rebuilt when the wall-clock target
changes; WorkManager provides an earliest eligible time rather than exact-alarm
delivery, so Android may still run it later.

### Background reliability diagnostics

`SettingsViewModel` reads `BackgroundReliabilityReader` when Settings first
appears and whenever the screen resumes. The reader uses only stable
`Build.MANUFACTURER` classification plus the public Android API 28+
`ActivityManager.isBackgroundRestricted()` and calling-app
`UsageStatsManager.appStandbyBucket` signals. It requests no usage-history
permission, stores no device-state values, and does not change WorkManager
scheduling.

The pure classifier reports a critical restriction, possible standby deferral,
or an unknown signal conservatively. Xiaomi-family guidance is shown only for a
normalized Xiaomi manufacturer; Black Shark is not treated as HyperOS. Even
when Android reports no restriction and an ACTIVE/EXEMPTED-or-lower bucket,
Xiaomi users must manually verify No restrictions and Background autostart
because Checky cannot reliably read that HyperOS toggle. If a concrete UX need
requires an additional Xiaomi/HyperOS permission or setting, the product may
document an explicit user-granted, reversible step instead of rejecting it
solely because it is OEM-specific or powerful. These diagnostics are
device-health guidance only, not Provider or live check-in verification.

### Connect flow (credentials)

- `ConnectProviderScreen` (FLAG_SECURE, secrets hidden, validated before save).
- `ConnectProviderViewModel` validates via `provider.validateCredentials(...)`, saves through the default Keystore-backed `CredentialStore`, and supports per-provider delete.
- The six production providers are registered directly in `ProviderModule`: HoYoverse Genshin (miyoushe), miyoushe community, HoYoverse ZZZ (experimental), Taygedo NTE, Taygedo community, and the bounded TapTap game-sign provider. Debug builds register only the TapTap provider; the full set is release-only. QR login, SMS login, and game-role selection are opt-in capability interfaces implemented only where supported; they are not a plugin marketplace.
- The miHoYo providers (Genshin, miyoushe community, ZZZ) each own a separate credential entry and their own QR flow. Disconnecting one removes only its own session.
- The two Taygedo providers share one Keystore-backed session entry, `taygedo.shared.session`, through `TaygedoClient`; disconnecting either removes that shared session.
- Fail-closed classification applies to response and state parsing: malformed, unknown, ambiguous, authentication-expired, or verification-required results stop the relevant flow. In multi-step mutation flows, only a confirmed `Success` or `AlreadyCompleted` permits the next mutation. Taygedo NTE and Taygedo community perform provider-specific sign-in state reads before their mutations; HoYoLAB submits its once-daily sign-in after confirming that a session is present.
- TapTap game-sign is the sole bounded UI-assisted exception: during a pending Checky run, its user-enabled AccessibilityService processes only `com.taptap`, requires the expected game-sign page/state markers, waits for recognized settling states, and allows at most one `立即签到` click. Auth, verification, ambiguous, duplicate, and malformed states fail closed; it does not provide generic arbitrary-app automation. Physical validation on the owner's Xiaomi/HyperOS device verified the Ready -> one gesture -> positive-terminal path and the AlreadyCompleted -> automatic Checky return path when the user-granted HyperOS background-popup setting was enabled. This evidence remains device/account/event scoped.

## Design decisions

| Decision | Rationale |
|---|---|
| `minSdk 26` | Supports the current Xiaomi/HyperOS target and gives a reliable Keystore + notification baseline; broader OEM/API coverage is not a current product requirement. |
| Provider contract = streaming `Flow<CheckInEvent>` | Gives live progress for free and is easy for real providers to implement (retrofit calls inside the flow). |
| Sealed `CheckInOutcome` instead of raw statuses at the boundary | Forces every provider to return **safe, user-facing** results (message + code + retry hint), never raw exceptions/headers/bodies. |
| `CredentialStore` abstraction | The in-memory implementation is test-only infrastructure; production always binds the Keystore AES/GCM vault. |
| Single `CheckInAllUseCase` | One place for concurrency limits, duplicate guard, continue-on-failure, cancellation, persistence, summary — unit-testable with fakes. |
| Direct OkHttp for current providers | The four current HoYoverse, HoYoLAB, and Taygedo providers use provider-local OkHttp calls with HTTPS/host-policy checks; Retrofit remains available for a future stable integration. |

## Testing strategy

- **JVM unit tests** (deterministic, `runTest` + fake stores/repos): use-case orchestration (all-providers, continue-on-failure, sequential mode, duplicate guard, cancellation), experimental-provider response mapping, malformed-state fail-closed behavior, browse-task allowlisting, credential lifecycle + isolation + delete-all, redaction, host allowlist, Room DAO (Robolectric), both DataStore-backed preference stores, and ViewModel state transitions. These do not verify live third-party accounts.
- **Instrumented tests** (run on device/emulator): Room persistence + v1→v2 migration, and an onboarding → dashboard → catalog UI smoke flow that never triggers real mutations. The targeted API 34 emulator acceptance passed for fresh-install and unconnected Home states; the full connected suite currently remains unverified after a timeout.
