# Checky

> **Your daily check-in buddy.**

A local-first Android **MVP** that aggregates daily check-ins from different
apps and services into one friendly dashboard, then triggers them with one tap.

**Package:** `com.checky.app` · **minSdk:** 26 · **targetSdk/compileSdk:** 35

> ⚠️ **Naming note:** this repo is built as `com.checky.app` (the brand name and
> primary spec). An earlier draft of the brief mentioned `com.dailydone.app` in
> the tech-stack section; the package has not been renamed.

---

## Product purpose

- See all connected check-in services in one dashboard.
- See whether each has checked in today (done / remaining / needs attention).
- Tap **"Check in all"** — every enabled service runs with live progress, one
  failure never stops the others, and a final summary shows rewards earned.
- Watch each service transition through Pending → Running → Success /
  Already checked in / Login expired / Failed / Action required.
- Retry a failed service individually, reconnect expired sessions, browse history.
- Credentials stay **on the device**.

## Current status

- **Five implemented production providers**: Miyoushe Genshin sign-in,
  Miyoushe community sign-in, Taygedo NTE game sign-in, Taygedo community
  sign-in (APP + section), and a bounded TapTap game-sign provider. Historical
  live verification of the four HTTP providers was recorded on 2026-08-30 using
  the owner's accounts; it is date- and account-scoped evidence, not a current
  availability guarantee. They use non-official HTTP surfaces, may stop
  working after platform changes, and perform no likes, comments, shares,
  redemptions, CAPTCHA handling, or risk-control workarounds.
- No cloud account, no sync, no remote credential storage, no analytics.
- TapTap is the sole narrow UI-assisted exception: during a pending Checky
  TapTap run, a user-enabled AccessibilityService observes only `com.taptap`,
  recognizes the expected game-sign page, waits for recognized settling states,
  and permits at most one check-in click. Auth, verification, ambiguous, and
  malformed states fail closed; arbitrary-app automation is out of scope.
- TapTap currently targets one fixed, verified game-sign event URL. Its physical
  AlreadyCompleted path is verified; the real Ready -> click -> Success path is
  not yet live-verified. A different or new event URL requires a provider update
  or separate discovery work.
- Credentials use the **Keystore-backed encrypted** implementation. The
  in-memory mock store remains available for JVM tests only.
- Optional daily **reminder** via WorkManager, plus an explicitly opt-in
  background auto-check-in schedule for selected and connected providers. A
  changed wall-clock target rebuilds the periodic request; Android may still
  run it later than the selected time because WorkManager is not an exact alarm.
  Android or OEM background restrictions can delay or block execution. A
  2026-09-04 physical-device experiment on Xiaomi/HyperOS observed this
  device-level reliability dependency; that evidence is scoped to the tested
  device/OS setup and did not isolate the causality of individual toggles. It
  is not live Provider/check-in verification. Checky can read public Android
  background-restriction and calling-app standby signals, but cannot reliably
  read the HyperOS Autostart toggle; Xiaomi-family users must manually choose
  No restrictions for battery / background use and enable Background autostart
  in system settings.
- Genshin roles are auto-fetched from the miyoushe binding API after QR
  connect; manual UID entry is only a fallback.
- Current validation (2026-09-04): 172 JVM tests passed with 0 failures,
  errors, or skips; `assembleDebug` passed; `lintDebug` passed with 18 warnings
  and 0 errors. Targeted API 34 emulator UI checks passed for fresh-install and
  unconnected states. The full `connectedDebugAndroidTest` suite timed out and
  remains unverified.

## Setup & build

Requirements: JDK 17+, Android SDK (platform 35), Android Studio, or the included Gradle 9.5 wrapper.

```bash
# Build the debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Run the JVM unit tests (no device needed)
./gradlew testDebugUnitTest

# Instrumented tests (device/emulator required)
./gradlew connectedDebugAndroidTest
```

> The project uses AGP 9.3.2, built-in Kotlin 2.2.10, and the included Gradle
> 9.5 wrapper. Android Studio can sync directly.

## Screens & flows

| Screen | What it does |
|---|---|
| **Onboarding** | Explains local-first privacy, that not every service is supported, to connect only your own accounts, and that services may break after platform changes. |
| **Home dashboard** | Date, done/remaining/attention counts, reward summary, big "Check in all" button (with progress + Cancel), per-service cards with live status/reward/retry/reconnect. |
| **Add services** | Catalog of the five real providers with connection type, risk level and credential type; every experimental provider is explicitly marked high risk. |
| **Connect provider** | Explains what data is required and where it is stored; hidden-by-default secret field, validation before save, per-provider delete, FLAG_SECURE. |
| **History** | Date, provider, status, reward, duration, safe diagnostic code; clear-history action. |
| **Settings** | Theme, daily reminder (time picker), run mode (parallel ≤3 / sequential), background-reliability health card, clear history, **delete all credentials**, privacy & security explanation. |
| **Provider details** | Status, last result, reward, enable toggle, "Manage connection". |

## Security model (summary — full details in SECURITY.md)

- Credentials: provider-scoped or explicitly shared provider-family storage through `CredentialStore`; the production implementation is **Android Keystore AES-256/GCM**; never logged, never backed up (`allowBackup=false`). Miyoushe Genshin and Miyoushe community sessions are separate; both Taygedo providers share `taygedo.shared.session`.
- Network: **HTTPS only**, per-provider **host allowlist**, and a **redacting interceptor** (Authorization, Cookie, tokens, API keys…); body logging is disabled in release. The experimental providers use direct OkHttp calls and remain subject to upstream API changes.
- Provider state is fail-closed: malformed, unknown, ambiguous, expired, or verification-required results stop the relevant mutation flow and surface reconnect/action-needed guidance.
- No arbitrary AccessibilityService or silent cross-app control: the sole narrow exception is the user-enabled, pending-run TapTap game-sign service restricted to `com.taptap` and the expected page/state markers. Root, CAPTCHA/anti-bot bypass, verification bypass, and automated financial actions remain **explicitly out of scope**.
- Credential screens use FLAG_SECURE; no analytics or crash reporting.

## What is intentionally not supported

- Arbitrary UI-automation providers and any form of silent cross-app control; TapTap is limited to the bounded exception described above.
- Any check-in that is not a deterministic action from the foreground flow or the user's explicit opt-in background schedule.
- Purchases, redemptions, lotteries, posting, messaging, or financial actions.
- Bypassing platform verification, CAPTCHA, or anti-abuse systems.
- Cloud accounts, backups of credentials, or off-device storage of any kind.

## Roadmap

### Done

1. Release hardening: signed release build from an untracked `keystore.properties`
   (falls back to the debug key without it), `lint` baseline, and CI.
2. CI matrix: `Unit tests & lint` (`testDebugUnitTest`, `assembleRelease`,
   `lintRelease`) plus an instrumented-test job targeting an API 34 emulator.
3. Instrumented coverage for the **Keystore credential store**.
4. Background auto-check-in (opt-in, 24 h periodic), auto-retry and reconnect
   notifications, history heatmap, and a home-screen widget.

### Next

5. Maintain the unofficial integrations: when a provider breaks, follow the
   documented evidence-gradient diagnosis (see TEST_REPORT.md). *(ongoing)*
6. Per-provider schedule (e.g. "only weekdays") — the current auto-check-in is a
   single global 24 h periodic work request.
7. Provider health checks (declared hosts reachable, API versions).
8. Reminder notification polish.

## minSdk 26 — why

- Covers ~98% of active devices and all devices from Android 8.0 (2017) up.
- Gives a reliable Android Keystore (AES/GCM) baseline, modern notification
  APIs, and current Compose/Kotlin toolchain support.
- API < 26 is negligible for a validation prototype and would complicate
  security-relevant code paths.

## Docs

- [ARCHITECTURE.md](ARCHITECTURE.md) — layers, flows, design decisions.
- [SECURITY.md](SECURITY.md) — trust model, threat boundaries, credential rules.
- [PROVIDER_DEVELOPMENT.md](PROVIDER_DEVELOPMENT.md) — adding a real provider + compliance checklist.
