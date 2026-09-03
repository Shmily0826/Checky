# Checky Test Report

## Current validation checkpoint

Recorded 2026-09-02 on Windows with JDK 17 (Temurin 17.0.19) and the
repository's Gradle 9.5.0 wrapper:

- `:app:testDebugUnitTest`: **154 tests, 0 failures, 0 errors, 0 skipped**.
  The count is the sum of the 21 XML result suites below.
- `:app:assembleDebug`: passed.
- `:app:lintDebug`: passed, 16 warnings and 0 errors.
- Targeted API 34 emulator acceptance passed for fresh-install and selected-
  but-unconnected Home states. No Provider authentication or network traffic
  was used.
- A full `connectedDebugAndroidTest` attempt timed out after approximately
  304 seconds without a completed result summary. It is **unverified**, not a
  pass, and is not included in the JVM count.

Current tracked toolchain: AGP 9.3.2, Gradle 9.5.0, and built-in Kotlin 2.2.10.

## Verification levels

Implemented, JVM-unit-tested, build-tested, emulator-tested,
physical-device-tested, and live-Provider-verified are separate claims. JVM
tests and builds do not verify third-party accounts. The live records below
are historical, date- and account-scoped evidence from 2026-08-30; they are not
current availability guarantees.

## JVM suite results

| Suite | Tests | Result |
|---|---:|---|
| `data.local.CheckyDaoTest` | 9 | passed |
| `data.network.HostPolicyTest` | 6 | passed |
| `data.network.RedactingLoggingInterceptorTest` | 5 | passed |
| `data.preferences.UserPreferencesRepositoryTest` | 3 | passed |
| `data.repository.CheckInRepositoryImplTest` | 10 | passed |
| `data.security.CredentialStoreTest` | 5 | passed |
| `data.work.AutoCheckInWorkerTest` | 1 | passed |
| `di.ProviderModuleTest` | 1 | passed |
| `domain.CheckInAllUseCaseTest` | 7 | passed |
| `domain.model.CheckInOutcomeTest` | 5 | passed |
| `domain.model.DomainModelsTest` | 12 | passed |
| `domain.ProviderConnectionGateTest` | 3 | passed |
| `domain.providers.ProviderParsingTest` | 9 | passed |
| `domain.providers.ProvidersTest` | 25 | passed |
| `ui.screens.addservice.AddServiceViewModelTest` | 3 | passed |
| `ui.screens.connect.ConnectProviderViewModelTest` | 25 | passed |
| `ui.screens.history.HistoryHeatmapTest` | 4 | passed |
| `ui.screens.history.HistoryViewModelTest` | 2 | passed |
| `ui.screens.home.HomeViewModelTest` | 6 | passed |
| `ui.screens.provider.ProviderDetailsViewModelTest` | 4 | passed |
| `ui.screens.settings.SettingsViewModelTest` | 9 | passed |
| **Total** | **154** | **0 failures/errors/skipped** |

The recorded 2026-09-02 run above predates the current source inventory. A
mechanical count of top-level `@Test` annotations in `app/src/test/java` is
currently **160** after the `a658921` follow-up coverage and the
`CHECKY-20260903-1401` Provider Details regression test. This task freshly ran
the current 160-test `:app:testDebugUnitTest` suite successfully (Gradle
`BUILD SUCCESSFUL`, exit 0) and `:app:assembleDebug` successfully; those fresh
results are separate from the historical 154-test run recorded above.

The successful JVM run also printed a non-fatal Robolectric/Room invalidation
tracker background-teardown exception (`Illegal connection pointer`) after
the test task completed. It did not change the exit code or test result. Its
stack traces run through the existing Settings/ReminderWorker test harness,
not the Provider Details changes; no Room production refactor is implied by
this task. The run should therefore not be described as warning-free or
exception-free.

The provider count is four actual `CheckInProvider` integrations: Miyoushe
Genshin, Miyoushe community, Taygedo NTE game, and Taygedo community. QR, SMS,
game-role, parsing, and orchestration checks are capabilities or test scopes,
not additional providers.

## Instrumented tests

The repository contains 13 instrumented test methods across Room persistence,
Keystore storage, and UI smoke classes. A historical 2026-08-30 run on the
`Checky_Android14` API 34 emulator reported 5/5 pass. The later full connected
suite timeout recorded above remains unverified. The 2026-09-02 targeted UI
acceptance verified onboarding, fresh Home, History empty state, Settings, Add
services, and selected-but-unconnected Home behavior only.

## Historical live Provider verification

The following four production providers were live verified on 2026-08-30 with
the owner's accounts. This record is preserved as historical evidence and was
not re-run by the current documentation task:

| Provider | Historical result |
|---|---|
| Taygedo NTE game sign-in | live verified; repeat correctly reported already done |
| Taygedo community sign-in | live verified after a transport fix; state preflight skipped repeat mutation |
| Miyoushe Genshin sign-in via web QR | live verified; repeat correctly reported already done |
| Miyoushe community sign-in via app QR | live verified |

Associated historical authentication/session evidence includes Taygedo SMS
login and shared-session establishment, the Miyoushe QR login paths, FLAG_SECURE
behavior, and an unconnected provider failing closed without mutation. These
are auth/capability checks, not additional providers, and do not authorize or
claim current live verification.

## Safety and limitations

- Credentials remain local-only in the Keystore-backed production store; JVM
  tests use fakes. No credentials, tokens, cookies, SMS codes, or QR tickets
  belong in this report.
- Provider execution is allowlisted to daily sign-in/state behavior. Likes,
  comments, shares, follows, posts, redemptions, lotteries, and hidden social
  interactions are out of scope.
- Malformed, unknown, ambiguous, expired, and verification-required states
  fail closed before subsequent mutation.
- The Home and opt-in background/widget paths require both user selection and
  a connected/executable provider. A catalog default or Room row alone is not
  proof of connection.
- No physical-device verification was performed for the 2026-09-02 task, and
  no Provider authentication, SMS, QR, check-in, reward, or network mutation
  was performed.
