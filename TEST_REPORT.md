# TEST_REPORT — JVM Unit Test Coverage

> Last run: 2026-08-22 · JDK 17 (Temurin 17.0.19) · Gradle 8.9 · Windows
> Command: `./gradlew testDebugUnitTest` · Result: **119 tests, 0 failures, 0 errors, 0 skipped**

## Verification levels (keep these distinct)

This report covers **JVM unit tests only**. The following labels from AGENTS.md
are NOT claimed here: connected Android tests, emulator tests, physical-device
tests, or live provider verification. A passing unit test suite does not prove
that a live provider works.

## Suite results

| Suite | Tests | Status | Scope |
|---|---|---|---|
| `data.network.HostPolicyTest` | 6 | ✅ | HTTPS-only host allowlist, malformed URL rejection |
| `data.network.RedactingLoggingInterceptorTest` | 5 | ✅ | Query/header/body secret redaction |
| `data.preferences.UserPreferencesRepositoryTest` | 3 | ✅ | DataStore defaults, all setters round-trip, snapshot consistency |
| `data.preferences.MockScenarioStoreImplTest` | 3 | ✅ | Dev scenario store default / round-trip / corrupted-value fallback |
| `data.repository.CheckInRepositoryImplTest` | 12 | ✅ | Result persistence, enable-flag preservation, seeding idempotency, reset, fail-safe mapping of malformed persisted enums |
| `data.security.CredentialStoreTest` | 5 | ✅ | Mock store lifecycle, per-provider isolation, delete/deleteAll |
| `domain.CheckInAllUseCaseTest` | 5 | ✅ | Orchestration: summary, continue-on-failure, sequential mode, duplicate guard, cancellation |
| `domain.model.CheckInOutcomeTest` | 5 | ✅ | Outcome→status mapping, retry recommendations, user-facing messages |
| `domain.model.DomainModelsTest` | 11 | ✅ | CheckInStatus classification, Reward labels/emptiness, CheckInResult delegation, summary arithmetic |
| `domain.providers.ProvidersTest` | 21 | ✅ | Mock provider outcomes, scenario overrides, Taygedo/Miyoushe mapping helpers, fail-closed sign-in sequencing, credential isolation |
| `domain.providers.ProviderParsingTest` | 9 | ✅ | Taygedo community classify matrix, NTE state parsing, failure mapping, Miyoushe community response shapes (incl. malformed fail-closed) |
| `domain.providers.MockOutcomesTest` | 3 | ✅ | Scenario→outcome mapping matrix, messages never leak exception text |
| `ui.screens.home.HomeViewModelTest` | 3 | ✅ | Running→Finished transitions, single retry, cancellation |
| `ui.screens.addservice.AddServiceViewModelTest` | 3 | ✅ | Catalog streaming, enable toggle delegation |
| `ui.screens.connect.ConnectProviderViewModelTest` | 19 | ✅ | Secret form (blank/valid/invalid), delete+disconnect, QR login (confirm/wait/expire/unsupported/cancel), SMS login, game-account binding |
| `ui.screens.history.HistoryViewModelTest` | 2 | ✅ | Records streaming, clear-history delegation |
| `ui.screens.provider.ProviderDetailsViewModelTest` | 4 | ✅ | Meta resolution, service stream, enable toggle |
| **Total** | **119** | ✅ | |

## What was added in commit `1e652ac`

Ten new test files (69 tests) covering previously untested layers: the
Room-backed repository (via an in-memory fake DAO), both DataStore-backed
preference stores, four ViewModels, pure domain-model logic, and the
JSON parsing / response-classification helpers of the experimental providers.
**No production code was changed.**

One test-infrastructure change was required: `testImplementation("org.json:json")`
(see `gradle/libs.versions.toml`), because the android.jar `org.json` stub
throws `"not mocked"` on the JVM and JSON-parsing fail-closed behavior cannot
be tested without a real implementation. This dependency is test-only and
never enters the APK.

## Known limitations / gaps

- **SettingsViewModel** is not JVM-tested: its constructor requires an Android
  `Context` (WorkManager scheduling paths). Needs Robolectric or an
  instrumented test.
- **KeystoreCredentialStore** is only exercised on-device (see
  `app/src/androidTest`); the JVM suite covers the mock store only.
- **Instrumented tests** (`CheckInAllUiTest`, `RoomHistoryPersistenceTest`)
  are compile-verified but were not run in this cycle — no device/emulator
  was available. Run `./gradlew connectedDebugAndroidTest` to close this gap.
- **No live verification**: all provider tests use fakes and scripted
  responses. The experimental Miyoushe/Taygedo providers remain
  implemented + unit-tested only, not live-verified.

## Notes for future test authors

- `kotlinx-coroutines-test` 1.8.1: `advanceUntilIdle()` only drains
  **foreground** scheduler tasks. Coroutines launched in
  `backgroundScope` are background tasks and will silently not run.
  Collect `stateIn(...)` flows in tests with a foreground
  `async { flow.first { ... } }` collector instead (see
  `HistoryViewModelTest` for the pattern). `HomeViewModelTest`'s
  `backgroundScope` collectors only work because foreground coroutines keep
  the scheduler busy — do not copy that pattern blindly.
- Build on this machine requires `JAVA_HOME` pointing to JDK 17 and
  `GRADLE_USER_HOME` set to the repo-local `.gradle-user-home` for offline
  dependency reuse.
