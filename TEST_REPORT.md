# TEST_REPORT — Test Coverage & Verification Status

> Last full run: 2026-08-30 · JDK 17 (Temurin 17.0.19) · Gradle 8.9 · Windows
> Command: `./gradlew testDebugUnitTest` · Result: **154 tests, 0 failures, 0 errors, 0 skipped**
> (137 tests in the committed tree at the time of writing; 13 additional
> Taygedo tests exist as uncommitted working-tree changes pending live
> verification. The committed-tree suite was not rerun after this count.)
> Instrumented run: 2026-08-30 on emulator `Checky_Android14` (API 34) — **5/5 pass**.
> Lint: `./gradlew lintDebug` — **0 errors, 74 warnings** (mostly
> dependency-upgrade suggestions; AGP/Compose BOM upgrades left as future work).
> Instrumented run: 2026-08-29 on emulator `Checky_Android14` (API 34) — see below.

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

## Instrumented tests (connectedDebugAndroidTest, emulator Checky_Android14 / API 34)

**All 5 pass** as of 2026-08-30, after two fixes:

1. **espresso-core 3.5.0 → 3.6.1** (pinned explicitly in `libs.versions.toml`):
   compose BOM 2024.06.00 pulls espresso 3.5.0, whose InputManager-based event
   injection crashes on modern emulator images (`NoSuchMethodException:
   android.hardware.input.InputManager.getInstance` at framework init).
2. **Onboarding race fix in `CheckInAllUiTest`**: on a cold emulator start the
   test checked for the onboarding button before the first frame composed,
   silently skipped the onboarding flow, and then failed to find "Check in
   all". The test now waits until either screen is present.

| Test | Status |
|---|---|
| `RoomHistoryPersistenceTest.clearHistoryRemovesAllRecordsButKeepsServices` | ✅ |
| `RoomHistoryPersistenceTest.savesAndReadsRecordIncludingSafeFields` | ✅ |
| `RoomHistoryPersistenceTest.historyOrdersNewestFirst` | ✅ |
| `RoomHistoryPersistenceTest.migrationFromV1KeepsExistingHistory` | ✅ (required Room schema export; `1.json` is a hand-derived v1 schema — v2 minus the two migrated columns — because no v1 commit exists in history) |
| `CheckInAllUiTest.checkInAllRunsProvidersAndShowsSummary` | ✅ |

## Live verification (2026-08-30, emulator, user's own account)

Status of the uncommitted Taygedo working-tree changes after real-account
exercise:

| Flow | Result | Evidence |
|---|---|---|
| Taygedo SMS login (shared session) | ✅ works | phone + server SMS code, "Connected" |
| 异环游戏签到 (NTE game sign-in) | ✅ **live verified** | `TAYGEDO_NTE_SUCCESS`, "异环签到成功（本月累计 2 天），获得 资深猎人攻略 ×3", 2069 ms; reward display confirms the signedDays-based reward index fix |
| 塔吉多社区签到 (community sign-in, step 1 异环 APP 签到) | ❌ **blocked server-side** | `TAYGEDO_COMMUNITY_FAILURE` — server returns "系统错误" on both attempts (13:28, 13:29); fail-closed correctly stopped step 2; the new server-detail passthrough in the failure message worked |
| FLAG_SECURE on connect screens | ✅ works | screencap returns an empty image on the connect screen |

The community provider's "系统错误" is unresolved live API behavior: per
AGENTS.md the Taygedo working-tree files remain **uncommitted** pending
investigation (e.g. re-check the current official app's APP-sign request).
The NTE half of the same files is verified working.

## Robolectric tests (JVM, no device needed) — added 2026-08-30

Nineteen new tests using the real Android framework classes on the JVM:

| Suite | Tests | Scope |
|---|---|---|
| `ui.screens.settings.SettingsViewModelTest` | 10 | Settings ViewModel end-to-end on Robolectric: DataStore preference round-trips, WorkManager scheduling via `work-testing` (`checky_daily_reminder` / `checky_auto_checkin`), repository + credential-vault actions |
| `data.local.CheckyDaoTest` | 9 | Real Room DAO on an in-memory database: upserts, ordering, partial `updateLastResult`, counts, entity mapping |

Two Robolectric-specific pitfalls are encoded in these tests and worth keeping
in mind for future ones:

- Room's default executors spawn real `arch_disk_io` threads that Robolectric's
  single-connection SQLite rejects (`Illegal connection pointer`) and whose
  uncaught exceptions poison other test classes. `CheckyDaoTest` uses direct
  executors so all DB access stays on the test thread.
- DataStore performs file IO on real `Dispatchers.IO` threads, which virtual
  time cannot drive and which a cancelled scope turns into leaked exceptions.
  `SettingsViewModelTest` therefore runs on real time (`runBlocking` + an
  eager unconfined Main + polling helpers `awaitPref`/`awaitWork`) and gives
  each DataStore a long-lived scope that is never cancelled mid-write.
- Mixing `runTest` into Robolectric classes is fragile for the same reason:
  coroutines-test reports other threads' uncaught exceptions as
  `UncaughtExceptionsBeforeTest`. Robolectric tests in this repo should use
  `runBlocking`.

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

- **KeystoreCredentialStore** is only exercised on-device/emulator; the JVM
  suite covers the mock store only. Android Keystore does not work under
  Robolectric, so a dedicated instrumented test is still the way to cover it.
- **No live verification**: all provider tests use fakes and scripted
  responses. The experimental Miyoushe/Taygedo providers remain
  implemented + unit-tested only, not live-verified.
- **Dependency upgrades** (lint warnings): AGP 8.7.0, compose BOM 2024.06.00
  and several androidx libraries have newer versions available. Upgrading the
  BOM would also lift ui-test beyond the espresso 3.5.0 pin workaround —
  worth doing in a dedicated change.

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
