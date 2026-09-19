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
| `data.work.AutoCheckInWorkerTest` | 2 | passed |
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
currently **172** after the `a658921` follow-up coverage, the
`CHECKY-20260903-1401` Provider Details regression test, and the
`CHECKY-20260903-1426` HoYoLAB QR parser coverage. The
`CHECKY-20260903-1519` gate coverage adds one test. This task adds eight
background-reliability classifier tests. A fresh run of the current
**172-test** suite passed, as did `:app:assembleDebug` and `:app:lintDebug`
(18 warnings, 0 errors). The historical 154-test result
above remains separate and unchanged.

## 2026-09-03 emulator-first scheduling and notification checkpoint

The source audit confirmed that both periodic schedules use unique names with
`CANCEL_AND_REENQUEUE`, local `Calendar` time calculation, and explicit 24-hour
periodic WorkManager semantics. The auto-check-in worker filters enabled,
connected providers through `ProviderConnectionGate`; this task additionally
made the worker re-check the persisted global auto-check-in opt-in immediately
before provider selection, closing a disable/cancellation race.

The existing notification implementation declares `POST_NOTIFICATIONS`, creates
one stable channel at application startup, checks permission before every post,
uses stable replacement IDs, and maps result summaries without raw provider
data. On `Medium_Phone` (SDK 37, x86_64, adb serial `emulator-5554`), the
focused runtime test passed for channel creation and granted delivery; a
separate host-denied run passed with `granted=false`, notifications disabled,
no crash, and `numPostedByApp=0`. The test-only WorkManager runtime check also
passed enable-equivalent enqueue, schedule replacement, and cancel, with one
unique work entry and a new work ID after replacement. Exact wall-clock firing
was not asserted because WorkManager is not an exact alarm. No Provider traffic,
credentials, QR/SMS flow, or check-in mutation was used.

The prior `CHECKY-20260903-1401` 160-test JVM run also printed a non-fatal
Robolectric/Room invalidation-tracker background-teardown exception
(`Illegal connection pointer`) after the test task completed. It did not
change that run's exit code or test result; its stack traces ran through the
existing Settings/ReminderWorker test harness, not the Provider Details
changes. The fresh `CHECKY-20260903-1426` 162-test JVM run did not reproduce
that exception. The fresh `CHECKY-20260903-1519` 163-test JVM run also did not
reproduce it. Neither result should be described as warning-free or
exception-free; no Room production refactor is implied by this task.

The provider count is four actual `CheckInProvider` integrations: HoYoverse
Genshin, HoYoLAB, Taygedo NTE game, and Taygedo community. QR, SMS,
game-role, parsing, and orchestration checks are capabilities or test scopes,
not additional providers.

## 2026-09-03 live verification

On the connected physical Xiaomi device (`2410DPN6CC`), the current debug APK
was installed with `adb install -r` after its signing certificate was matched
to the existing installation; application data was preserved. The user
completed one official HoYoLAB QR confirmation, and reported that the
community check-in succeeded. A subsequent read-only Checky UI inspection
showed `Connected`, `Success`, and `米游社讨论区签到成功，米游币 +30。` No second
check-in or other Provider mutation was performed.

An app-owned temporary sanitizer reported only field-presence booleans for the
persisted community session: `stoken`, `stoken_v2`, `mid`, `stuid`,
`account_id`, `account_id_v2`, `cookie_token_v2`, `ltoken`, `ltoken_v2`,
`ltuid`, and `ltmid_v2` were present. The QR persistence gate now accepts
exactly this observed live-verified field set; it does not require the
unconfirmed legacy `cookie_token` field. This successful
full-session result does not prove that a reduced stoken-only session is
sufficient; the community provider has no existing read-only endpoint that
distinguishes that case, and no reduced-session mutation was attempted. The
raw QR response was not retained, so this run does not expand the narrow
fail-closed parser contract or claim a newly observed response schema.

## 2026-09-03 lifecycle scheduling verification

On the `Medium_Phone` API 37 x86_64 emulator (`emulator-5554`), a temporary
test-only harness scheduled one future `checky_auto_checkin` periodic request
while the persisted global auto-check-in opt-in remained disabled. The direct
isolated WorkManager schedule/change/cancel method passed. After a host-side
background process kill, the Checky JobScheduler entry remained registered; an
explicit relaunch restored the app process without creating a duplicate unique
request. A host-side `am force-stop com.checky.app` removed the JobScheduler
entry, and an explicit launch caused WorkManager to register the same unique
work again. This is Android force-stop behavior, not an app-start reconciliation
implemented by Checky.

For an ordinary reboot with no force-stop immediately before reboot, the
pre-reboot entry was `JOB #u0a238/4 ... com.checky.app/...SystemJobService`.
After `boot_completed=1`, without launching `MainActivity`, six host polls at
10-second intervals all reported `stopped=false`/`notLaunched=false` and the
same Checky JobScheduler entry. This proves WorkManager reboot rescheduling on
this emulator; it does not prove exact wall-clock firing. The merged manifest
shows WorkManager's `RescheduleReceiver` for `BOOT_COMPLETED`, `TIME_SET`, and
`TIMEZONE_CHANGED`, plus package-replacement handling. Checky itself declares no
custom boot, time, or package receiver and has no explicit startup scheduling
reconciliation.

Source review found no demonstrated need to add reconciliation: disabled
preferences are rechecked by the worker (and stale work is cancelled by
Settings), changed times use `CANCEL_AND_REENQUEUE`, and WorkManager owns
process-death/reboot restoration. The theoretical state “enabled preference,
missing work” after an interrupted settings coroutine was not reproduced and
remains an unverified recoverability edge, not a proven defect. No Provider
traffic, credentials, authentication, or check-in mutation was used. Xiaomi/
HyperOS, physical-device, exact-alarm, and OEM battery-policy behavior remain
unverified.

The current Settings copy now makes the OS execution boundary explicit:
configured automatic check-in does not guarantee background execution, and
Xiaomi/HyperOS users may need to enable Autostart and unrestricted battery use
manually. No manufacturer-private intent or background-policy bypass is
implemented. A rebuilt APK was checked on the generic emulator with
UIAutomator: Settings rendered the guidance text in a bounded scrollable
content area (`[189,1221][859,1557]`), with no provider state changes.

## Instrumented tests

The repository contains 16 instrumented test methods across Room persistence,
Keystore storage, UI smoke, and WorkManager/notification runtime classes. A
historical 2026-08-30 run on the `Checky_Android14` API 34 emulator reported
5/5 pass. The later full connected suite timeout recorded above remains
unverified. The WorkManager schedule/change/cancel check passed in the earlier
focused connected instrumentation run. This task directly ran the granted and
denied notification methods in separate fresh instrumentation processes on
`Medium_Phone`; the production Settings automatic-check-in toggle was not
activated in this safety-bounded task. The existing targeted UI acceptance verified onboarding, fresh Home,
History empty state, Settings, Add services, and selected-but-unconnected Home
behavior only.

## Historical live Provider verification

The following four production providers were live verified on 2026-08-30 with
the owner's accounts. This record is preserved as historical evidence; the
current 2026-09-03 community re-verification is documented separately above.

| Provider | Historical result |
|---|---|
| Taygedo NTE game sign-in | live verified; repeat correctly reported already done |
| Taygedo community sign-in | live verified after a transport fix; state preflight skipped repeat mutation |
| HoYoverse Genshin sign-in via web QR | live verified; repeat correctly reported already done |
| HoYoLAB sign-in via app QR | live verified |

Associated historical authentication/session evidence includes Taygedo SMS
login and shared-session establishment, the HoYoLAB QR login paths, FLAG_SECURE
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

## 2026-09-04 background reliability safeguard

The Settings screen now exposes a local background-reliability card when
automatic check-in is enabled and diagnostics are relevant. It distinguishes
Android's detected background restriction, a standby bucket that may defer
WorkManager, and unavailable signals. Xiaomi-family copy explicitly treats No
restrictions and Background autostart as manual checks because the HyperOS
Autostart toggle is not reliably readable through the public APIs in scope.
The route uses the documented generic application-details Settings intent with a
generic Settings fallback; no opaque Xiaomi component is used.

Focused classifier coverage includes Xiaomi and non-Xiaomi restriction states,
unknown signals, ACTIVE/EXEMPTED bucket handling, and the Black Shark/foreign
manufacturer boundary. On 2026-09-04, physical UI/settings-route verification
was performed on Xiaomi `2410DPN6CC` / Android 16 / HyperOS
`OS3.0.308.0.WOBCNXM`. A fresh current debug APK was rebuilt with the existing
installed debug signer and installed successfully with a plain, data-preserving
`adb install -r`.

On the device, Checky Settings showed the card status `System restriction not
detected` and the manual guidance to set Xiaomi/HyperOS Battery/background use
to No restrictions and enable Background autostart. The documented generic
application-details Settings route opened HyperOS Checky App info; its system
UI showed Autostart enabled, but it was not changed and is not treated as
app-readable state. Back returned to Checky Settings, where the card and state
remained consistent after resume.

This was physical UI/settings-route verification only: it was not new
WorkManager dispatch verification and not Provider/live check-in verification.
No Provider authentication, network request, check-in mutation, or system
setting mutation occurred. The prior combined No restrictions + Autostart
dispatch experiment remains separate evidence and does not establish
individual-toggle causality. The current non-device validation passed: 172 JVM
tests, `assembleDebug`, and `lintDebug` with 18 warnings and 0 errors. These
results and the device UI check are not live-provider evidence.

## 2026-09-19 documentation sync (no code change)

Corrections to earlier statements in this report, recorded as documentation
only; no code was changed and no new validation was run.

- **Provider inventory.** The 2026-09-03 note above ("four actual
  `CheckInProvider` integrations") predates the current source. The release
  build now registers six providers: HoYoverse Genshin (miyoushe), miyoushe
  community, HoYoverse ZZZ (experimental), Taygedo NTE, Taygedo community,
  and the bounded TapTap game-sign provider. Debug builds register only the
  TapTap provider. The HoYoLAB app-QR live record of 2026-08-30 and the
  2026-09-03 community re-verification remain their own dated, scoped
  evidence.
- **ZZZ live verification status.** The HoYoverse ZZZ provider is
  unit-tested and shares the Genshin provider's structural pattern, but has
  no live verification record. It must not be described as live verified.
- **Stale-schedule reconciliation.** The 2026-09-03 lifecycle section stated
  that "no startup scheduling reconciliation" was implemented and left the
  "enabled preference, missing work" state as an unverified edge. This was
  superseded by the auto check-in stale-schedule reconciliation
  implementation (commit `3836c6d`), which reconciles stale work on worker
  runs. No fresh validation of that behavior is claimed here.
- **UI automation scope docs.** PROVIDER_DEVELOPMENT.md previously described
  UI-assisted providers as "Not supported yet"; it now documents the sole
  bounded TapTap exception, consistent with ARCHITECTURE.md and SECURITY.md.

## 2026-09-19 CI workflow change (no app code change)

Every push since at least 2026-09-13 produced a failed CI run: the
instrumented-tests job ran to the default 6-hour job limit (matching the
long-standing full connected-suite timeout), and from 2026-09-19 jobs stopped
starting entirely due to a GitHub Actions billing failure on the account —
not a code or test failure.

`.github/workflows/ci.yml` was changed accordingly: the instrumented-tests
job is now manual-only (`workflow_dispatch`) with a 45-minute timeout, the
unit/lint job has a 30-minute timeout, and a concurrency group cancels
superseded runs on the same ref. No app code was changed and no validation
was run in this task; the change is build-infrastructure configuration only.

## 2026-09-19 unit-test hang fix and first full green suite

### Root cause of the CI unit-test hang (supersedes the "suspected new test"
### note in the section above)

`TapTapProviderTest` used the synthetic English text "verification required"
as a verification marker, but `TapTapPageMatcher.isVerificationMarker`
(unchanged since `f23ba36`) only recognizes Chinese markers plus "captcha".
The ambiguous snapshot was therefore classified `WAITING`, not `UNKNOWN`, and
the test's `run.completion.await()` suspended forever — a deterministic
deadlock that reproduced locally and on every CI run since 2026-09-13. Fix
(fc03fd6): "verification" added to the marker list, strengthening fail-closed
classification. A thread dump of the Gradle test worker (`jstack`) located the
deadlocked `Sandbox.runOnMainThread` / `runBlocking` pair.

### Second latent failure uncovered once the suite could complete

`HomeViewModelTest.checkInAllSkipsTaygedoWhenValidHealthFailsPreflight`
failed twice: it needs an Android `Context` (class is now
`@RunWith(RobolectricTestRunner)` `@Config(sdk = [34])`, matching the existing
`SettingsViewModelTest` pattern), and the Taygedo preflight request runs on
real `Dispatchers.IO`, which `advanceUntilIdle()` virtual time does not cover
(the test now polls on the real clock, same pattern as `SettingsViewModelTest`).

### Current validation baseline (2026-09-19)

- JVM: **322 tests, 0 failures/errors** (`testDebugUnitTest`, local run,
  JDK 17 Temurin 17.0.19). This supersedes the 172-test baseline above; the
  suite grew without a recorded full-suite run because it could not complete.
- CI: first green push-triggered run since 2026-09-13 (fc03fd6, 6m05s,
  unit + lint).
- Instrumented: on emulator API level per AVD, **16/16 tests passed** for the
  first recorded time (previously timed out, then 14/16). Fixes: the debug
  build's start destination no longer overrides an incomplete onboarding
  (fresh debug installs see onboarding; the start destination is decided once
  from the first persisted preferences so the NavHost graph is not rebuilt
  mid-session), `CheckInAllUiTest` asserts the debug-visible catalog entry
  (TapTap only in debug), and the notification tests grant/revoke
  `POST_NOTIFICATIONS` through UiAutomation `pm grant/revoke` with a
  bounded state poll instead of relying on ambient grant state.
- Dependency bumps validated with the full JVM suite: espresso-core 3.7.0,
  robolectric 4.16.1, appcompat 1.8.0. `hilt-navigation-compose` stays at
  1.2.0 — 1.4.0 requires compileSdk 37 (app compiles against 35).
