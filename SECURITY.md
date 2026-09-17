# SECURITY.md — Checky trust model & threat boundaries

Checky is a **local-first check-in hub**. This document explains what it
trusts, what it protects, and what it deliberately refuses to do.

The primary deployment is one owner using one Xiaomi/HyperOS device and the
owner's accounts. Device-specific permissions or settings are not forbidden by
themselves: an explicitly user-granted background-popup, overlay, or
accessibility permission may be considered when it provides a concrete UX
benefit, provided the scope is documented, the permission is easy to revoke,
and the behavior remains fail-closed. This personal-device optimization does
not imply support for other OEMs or devices, and implementation/build/device/
live verification claims remain distinct.

---

## 1. Trust model

| Boundary | Trusted? | Notes |
|---|---|---|
| Your device (app sandbox) | ✅ Yes | All data lives here: Room DB, DataStore preferences, credential vault. |
| Android Keystore | ✅ Yes | Encryption keys never leave secure hardware / the Keystore. |
| Third-party services you connect | ⚠️ Conditional | Only through the provider's declared API hosts, HTTPS only, only on explicit user action. |
| The cloud | ❌ No | No cloud account, no remote storage, no sync, no backend. |
| Other apps on the device | ⚠️ Narrow exception | Checky never reads another app's credentials. A user-enabled AccessibilityService may observe only `com.taptap` during a pending TapTap game-sign run and may perform at most one allowlisted check-in click on the expected page. |
| Advertisers / analytics | ❌ No | No analytics, no crash reporting, no ads, no trackers. |

The user is always in control: foreground check-ins are deterministic actions
initiated explicitly from the Checky UI. A separate background schedule exists
only after explicit opt-in and still runs only providers that pass the
selected-and-connected execution gate.

The Settings background-reliability card reads only local, public Android
signals: a background-restriction boolean and the calling app's standby bucket.
These values are not persisted or sent anywhere. They can identify some Android
execution risk, but they cannot reliably reveal the HyperOS Background autostart
toggle; Xiaomi-family users must verify No restrictions and Background autostart
manually. This check does not authenticate, contact, or mutate a Provider.

## 2. Data inventory

| Data | Where stored | Backed up? |
|---|---|---|
| Check-in history (status, reward, safe diagnostic code) | Room (`checky.db`) | No — `allowBackup=false` |
| Preferences (theme, reminder, run mode, auto-check-in opt-in) | DataStore (`user_prefs.pb`) | No |
| Provider credentials | `CredentialStore`; default implementation = Android Keystore AES/GCM + private file | No (Keystore key is non-exportable) |
| Logs | Debug-only println | Never contains secrets |

## 3. Credential security (mandatory rules)

1. Credentials use provider-scoped or explicitly shared provider-family entries — unrelated providers cannot read another provider's vault entry; the two Taygedo providers intentionally use the shared `taygedo.shared.session` entry.
2. The real implementation encrypts with **AES-256/GCM** where the key is generated inside **Android Keystore** (non-exportable).
3. Secrets are **never** written to SharedPreferences, logs, Git, fixtures, or screenshots.
4. The credential-entry screen sets **`FLAG_SECURE`** so it can't be captured in screenshots/recents.
5. **"Delete all credentials"** wipes the whole vault; each provider also supports deleting its own.
6. Android cloud backup is disabled for the whole app (`allowBackup="false"`).

## 4. Network security

- **HTTPS only** — the manifest sets `usesCleartextTraffic="false"`.
- Every provider declares an **allowlist of hosts** (`ProviderMeta.allowedHosts`) and the networking layer
  (`HostPolicy`) refuses any request to a host outside it, and any non-HTTPS URL.
- Network logging goes through `RedactingLoggingInterceptor`, which masks
  `Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`, `X-Session-Token`,
  and query params `access_token`, `refresh_token`, `session`, `api_key`, `code`, `secret`, `token`, `key`, `auth`.
- Body logging is disabled in release builds.
- Raw headers, cookies, tokens and response bodies are **never** persisted to history.

## 5. What Checky refuses to do (hard scope)

- ❌ Bypass CAPTCHA, anti-bot systems, device verification, or platform security.
- ❌ Automate purchases, redemptions, lotteries, posting, messaging, or financial actions.
- ❌ Use AccessibilityService or silently open/control arbitrary other applications. The sole narrow exception is the user-enabled TapTap game-sign service, restricted to `com.taptap`, the expected page/state markers, one pending run, and at most one check-in click.
- ❌ Read credentials belonging to other installed apps.
- ❌ Use root or debugger hooks.
- ❌ Store or transmit any credential off-device.

Powerful or OEM-specific permissions are therefore a review question, not an
automatic prohibition. They must never be used to extract another app's
credentials, bypass CAPTCHA, anti-bot, verification, pinning, or risk-control
systems, automate unknown tasks, or continue after malformed, ambiguous,
authentication-expired, verification-required, or uncertain mutation state.

## 6. Threat boundaries (who could do what)

| Threat | Mitigation |
|---|---|
| Malicious provider code | Providers are isolated behind the `CheckInProvider` interface + host allowlist + redaction; experimental providers keep their side effects provider-local and allowlisted. |
| App compromise / rooted device | Keystore-backed keys are hardware-anchored; app refuses root features by design; no cloud target to exfiltrate to. |
| Shoulder-surfing / screenshots | FLAG_SECURE on credential screens; secrets hidden by default. |
| Backup extraction | `allowBackup=false` + `fullBackupContent=false`. |
| Supply chain (new provider) | See PROVIDER_DEVELOPMENT.md — every provider must pass the compliance checklist, declare hosts, and redact sensitive fields. |

## 7. Reporting

The production catalog contains four non-official, high-risk HTTP-session
providers: HoYoverse Genshin sign-in, HoYoLAB sign-in, Taygedo NTE
game sign-in, and Taygedo community sign-in, plus one bounded TapTap
UI-assisted game-sign provider. Dated live verification for the four HTTP
providers, plus associated login/session and fail-closed checks, is recorded in
`TEST_REPORT.md`; it is evidence for that tested scope and date, not a
guarantee of continued upstream availability. On the owner's Xiaomi/HyperOS
device, TapTap physical validation verified the Ready -> one gesture -> positive
terminal path and the AlreadyCompleted -> automatic Checky return path after
the user granted Checky HyperOS “Open new windows while running in the
background”. This remains device/account/event-scoped evidence, not a general
platform guarantee.

All providers store credentials locally and do not implement likes, comments,
shares, follows, posts, redemptions, CAPTCHA handling, or risk-control
workarounds. Provider tests cover malformed and unrecognized responses; they
must not be interpreted as live-provider verification.
If you find a real security issue in a future release, report it privately to
the maintainer; do not post credentials or raw traffic in public issues.
