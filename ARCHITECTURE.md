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
│  CheckInProvider (plugin contract)  ← extension point       │
│  CheckInEvent (Progress / Done)                             │
│  CheckInOutcome (Success/Already/AuthExpired/ActionRequired │
│                  /TemporaryFailure/PermanentFailure/Unsup.) │
│  CheckInAllUseCase (orchestrator: concurrency, guard,       │
│                  continue-on-failure, cancellation, summary)│
│  CredentialStore (per-provider vault contract)              │
│  MockScenarioStore (dev overrides)                          │
├─────────────────────────────────────────────────────────────┤
│  Data                                                        │
│  Room: check_in_records (history) + services (state)        │
│  DataStore: user prefs (theme, reminder, run mode, scenario)│
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
- `ConnectProviderViewModel` validates via `provider.validateCredentials(...)`, saves through `CredentialStore` (mock now, Keystore-backed later), supports per-provider delete.
- `CloudBoxProvider` checks the vault each run: no token → `AuthenticationExpired`; token present → success (+1 membership day).

### Developer mock controls

- `SettingsViewModel` writes a `MockScenario` to DataStore; every mock provider maps the scenario to an outcome via `outcomeForScenario(...)` (success / already / auth expired / network failure / action required).

## Design decisions

| Decision | Rationale |
|---|---|
| `minSdk 26` | Covers ~98% of active devices, allows modern Java/Kotlin APIs, and gives a reliable Keystore + notification baseline; API < 26 devices are negligible for a prototype. |
| Provider contract = streaming `Flow<CheckInEvent>` | Gives live progress for free and is easy for real providers to implement (retrofit calls inside the flow). |
| Sealed `CheckInOutcome` instead of raw statuses at the boundary | Forces every provider to return **safe, user-facing** results (message + code + retry hint), never raw exceptions/headers/bodies. |
| `CredentialStore` abstraction | The mock (in-memory) and real (Keystore AES/GCM) implementations are interchangeable in DI; tests use the deterministic mock. |
| Single `CheckInAllUseCase` | One place for concurrency limits, duplicate guard, continue-on-failure, cancellation, persistence, summary — unit-testable with fakes. |
| Mock outcome overrides in DataStore | Lets a developer force every failure/success state from Settings without touching provider code. |
| No Retrofit calls in the MVP | HTTP deps are declared and the redaction/allowlist layers are tested; real providers come later. |

## Testing strategy

- **JVM unit tests** (deterministic, `runTest` + fake stores/repos): use-case orchestration (all-providers, continue-on-failure, sequential mode, duplicate guard, cancellation), provider outcomes + scenario overrides, outcome mapping, credential lifecycle + isolation + delete-all, redaction, host allowlist, HomeViewModel state transitions.
- **Instrumented tests** (compile-verified; run on device/emulator): Room persistence + v1→v2 migration, Compose UI "Check in all" flow.
