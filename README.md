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

- **Four real providers, all live-verified** (2026-08-30, on emulator with the
  owner's accounts): Miyoushe Genshin sign-in, Miyoushe community sign-in,
  Taygedo NTE game sign-in, and Taygedo community sign-in (APP + section).
  They use non-official HTTP surfaces, may stop working after platform
  changes, and perform no likes, comments, shares, redemptions, CAPTCHA
  handling, or risk-control workarounds.
- No cloud account, no sync, no remote credential storage, no analytics.
- High-risk providers (UI automation) are out of scope entirely.
- Credentials use the **Keystore-backed encrypted** implementation. The
  in-memory mock store remains available for JVM tests only.
- Optional daily **reminder** via WorkManager, plus an explicitly opt-in
  background auto-check-in schedule for enabled providers. Android may run it
  later than the selected time.
- Genshin roles are auto-fetched from the miyoushe binding API after QR
  connect; manual UID entry is only a fallback.

## Setup & build

Requirements: JDK 17+, Android SDK (platform 35), Android Studio (or Gradle 8.9).

```bash
# Build the debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Run the JVM unit tests (no device needed)
./gradlew testDebugUnitTest

# Instrumented tests (device/emulator required)
./gradlew connectedDebugAndroidTest
```

> This machine originally had no Gradle wrapper; a workspace-local Gradle 8.9
> in `.buildtools/` was used for the first build and the wrapper has since been
> generated — Android Studio can sync directly.

## Screens & flows

| Screen | What it does |
|---|---|
| **Onboarding** | Explains local-first privacy, that not every service is supported, to connect only your own accounts, and that services may break after platform changes. |
| **Home dashboard** | Date, done/remaining/attention counts, reward summary, big "Check in all" button (with progress + Cancel), per-service cards with live status/reward/retry/reconnect. |
| **Add services** | Catalog of the four real providers with connection type, risk level and credential type; every experimental provider is explicitly marked high risk. |
| **Connect provider** | Explains what data is required and where it is stored; hidden-by-default secret field, validation before save, per-provider delete, FLAG_SECURE. |
| **History** | Date, provider, status, reward, duration, safe diagnostic code; clear-history action. |
| **Settings** | Theme, daily reminder (time picker), run mode (parallel ≤3 / sequential), clear history, **delete all credentials**, privacy & security explanation. |
| **Provider details** | Status, last result, reward, enable toggle, "Manage connection". |

## Security model (summary — full details in SECURITY.md)

- Credentials: per-provider `CredentialStore`; real impl is **Android Keystore AES-256/GCM**; never logged, never backed up (`allowBackup=false`).
- Network: **HTTPS only**, per-provider **host allowlist**, and a **redacting interceptor** (Authorization, Cookie, tokens, API keys…); body logging is disabled in release. The experimental providers use direct OkHttp calls and remain subject to upstream API changes.
- No AccessibilityService, no cross-app control, no root, no CAPTCHA/anti-bot bypass, no automated financial actions — **explicitly out of scope**.
- Credential screens use FLAG_SECURE; no analytics or crash reporting.

## What is intentionally not supported

- UI-automation providers and any form of silent cross-app control.
- Any check-in that is not a deterministic action initiated by the user.
- Purchases, redemptions, lotteries, posting, messaging, or financial actions.
- Bypassing platform verification, CAPTCHA, or anti-abuse systems.
- Cloud accounts, backups of credentials, or off-device storage of any kind.

## Roadmap

1. Release hardening: a signed release build, `lint` baseline, and CI
   (GitHub Actions emulator for `connectedDebugAndroidTest`).
2. Maintain the unofficial integrations: when a provider breaks, follow the
   documented evidence-gradient diagnosis (see TEST_REPORT.md).
3. Add instrumented coverage for the **Keystore credential store** on a real
   device or emulator.
4. Optional: reminder notification polish, per-provider schedule (e.g. "only
   weekdays"), and background retry policy for TemporaryFailure.
5. Maintained provider health checks (declared hosts reachable, API versions).

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
