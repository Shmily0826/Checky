# ARCHITECTURE.md — Checky

Local-first Android MVP. Kotlin, Jetpack Compose, Material 3, MVVM, Room,
DataStore, Coroutines/Flow, Hilt.

## Layered diagram

```
┌─────────────────────────────────────────────────────────────┐
│  UI (Compose, Material 3)                                   │
│  Onboarding · Home · Add services · Connect · History ·     │
│  Settings · Provider details                                │
│  ViewModels (StateFlow, viewModelScope, Hilt-injected)      │
├─────────────────────────────────────────────────────────────┤
│  Domain                                                      │
│  Four concrete providers: Miyoushe Genshin, Miyoushe       │
│  community, Taygedo NTE, Taygedo community                 │
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

1. `HomeViewModel.checkInAll()` filters providers to those **enabled** in Room.
2. It reads the user's **run mode**: `PARALLEL` (semaphore-limited to 3) or `SEQUENTIAL`.
3. `CheckInAllUseCase.invoke(providers, parallel)`:
   - sets an `AtomicBoolean` guard → duplicate triggers are ignored;
   - streams `CheckInAllProgress.Running(states)` every 40 ms while any job is alive;
   - each provider's `Flow<CheckInEvent>` is collected: `Progress` patches live UI state; `Done` persists the safe `CheckInResult` to Room;
   - a provider throwing is caught (except `CancellationException`) and mapped to a safe `TemporaryFailure` — **other providers continue**;
   - cancellation propagates cleanly and persists nothing;
   - finishes with one `CheckInAllProgress.Finished(states, summary)` (points/XP/membership days/durations).
4. Home renders live progress per service card and a final summary banner; individual **Retry** (FAILED) and **Reconnect → Connect screen** (LOGIN_EXPIRED) actions are available per card.

### Connect flow (credentials)

- `ConnectProviderScreen` (FLAG_SECURE, secrets hidden, validated before save).
- `ConnectProviderViewModel` validates via `provider.validateCredentials(...)`, saves through the default Keystore-backed `CredentialStore`, and supports per-provider delete.
- The four production providers are registered directly in `ProviderModule`: Miyoushe Genshin, Miyoushe community, Taygedo NTE, and Taygedo community. QR login, SMS login, and game-role selection are opt-in capability interfaces implemented only where supported; they are not a plugin marketplace.
- The Miyoushe Genshin and Miyoushe community providers intentionally own separate credential entries and separate QR flows. Disconnecting the community provider removes only its own session.
- The two Taygedo providers share one Keystore-backed session entry, `taygedo.shared.session`, through `TaygedoClient`; disconnecting either removes that shared session.
- Fail-closed classification applies to response and state parsing: malformed, unknown, ambiguous, authentication-expired, or verification-required results stop the relevant flow. In multi-step mutation flows, only a confirmed `Success` or `AlreadyCompleted` permits the next mutation. Taygedo NTE and Taygedo community perform provider-specific sign-in state reads before their mutations; Miyoushe community submits its once-daily sign-in after confirming that a session is present.

## Design decisions

| Decision | Rationale |
|---|---|
| `minSdk 26` | Covers ~98% of active devices, allows modern Java/Kotlin APIs, and gives a reliable Keystore + notification baseline; API < 26 devices are negligible for a prototype. |
| Provider contract = streaming `Flow<CheckInEvent>` | Gives live progress for free and is easy for real providers to implement (retrofit calls inside the flow). |
| Sealed `CheckInOutcome` instead of raw statuses at the boundary | Forces every provider to return **safe, user-facing** results (message + code + retry hint), never raw exceptions/headers/bodies. |
| `CredentialStore` abstraction | The in-memory implementation is test-only infrastructure; production always binds the Keystore AES/GCM vault. |
| Single `CheckInAllUseCase` | One place for concurrency limits, duplicate guard, continue-on-failure, cancellation, persistence, summary — unit-testable with fakes. |
| Direct OkHttp for current providers | The four current Miyoushe/Taygedo providers use provider-local OkHttp calls with HTTPS/host-policy checks; Retrofit remains available for a future stable integration. |

## Testing strategy

- **JVM unit tests** (deterministic, `runTest` + fake stores/repos): use-case orchestration (all-providers, continue-on-failure, sequential mode, duplicate guard, cancellation), experimental-provider response mapping, malformed-state fail-closed behavior, browse-task allowlisting, credential lifecycle + isolation + delete-all, redaction, host allowlist, Room DAO (Robolectric), both DataStore-backed preference stores, and ViewModel state transitions. These do not verify live third-party accounts.
- **Instrumented tests** (run on device/emulator): Room persistence + v1→v2 migration, and an onboarding → dashboard → catalog UI smoke flow that never triggers real mutations.
