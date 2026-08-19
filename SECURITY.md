# SECURITY.md — Checky trust model & threat boundaries

Checky is a **local-first check-in hub**. This document explains what it
trusts, what it protects, and what it deliberately refuses to do.

---

## 1. Trust model

| Boundary | Trusted? | Notes |
|---|---|---|
| Your device (app sandbox) | ✅ Yes | All data lives here: Room DB, DataStore preferences, credential vault. |
| Android Keystore | ✅ Yes | Encryption keys never leave secure hardware / the Keystore. |
| Third-party services you connect | ⚠️ Conditional | Only through the provider's declared API hosts, HTTPS only, only on explicit user action. |
| The cloud | ❌ No | No cloud account, no remote storage, no sync, no backend. |
| Other apps on the device | ❌ No | Checky never reads another app's credentials, never uses AccessibilityService, never controls other apps. |
| Advertisers / analytics | ❌ No | No analytics, no crash reporting, no ads, no trackers. |

The user is always in control: check-ins are **deterministic actions initiated
explicitly by the user** from the Checky UI. Nothing runs silently.

## 2. Data inventory

| Data | Where stored | Backed up? |
|---|---|---|
| Check-in history (status, reward, safe diagnostic code) | Room (`checky.db`) | No — `allowBackup=false` |
| Preferences (theme, reminder, run mode, mock scenario) | DataStore (`user_prefs.pb`) | No |
| Provider credentials | `CredentialStore`; default implementation = Android Keystore AES/GCM + private file | No (Keystore key is non-exportable) |
| Logs | Debug-only println | Never contains secrets |

## 3. Credential security (mandatory rules)

1. Credentials are stored **per provider** — one provider can never read another's vault entry.
2. The real implementation encrypts with **AES-256/GCM** where the key is generated inside **Android Keystore** (non-exportable).
3. Secrets are **never** written to SharedPreferences, logs, Git, fixtures, or screenshots.
4. The credential-entry screen sets **`FLAG_SECURE`** so it can't be captured in screenshots/recents.
5. **"Delete all credentials"** wipes the whole vault; each provider also supports deleting its own.
6. Android cloud backup is disabled for the whole app (`allowBackup="false"`).

## 4. Network security (for future real providers)

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
- ❌ Use AccessibilityService or silently open/control other applications.
- ❌ Read credentials belonging to other installed apps.
- ❌ Use root or debugger hooks.
- ❌ Store or transmit any credential off-device.

## 6. Threat boundaries (who could do what)

| Threat | Mitigation |
|---|---|
| Malicious provider code | Providers are isolated behind the `CheckInProvider` interface + host allowlist + redaction; MVP ships only mock providers. |
| App compromise / rooted device | Keystore-backed keys are hardware-anchored; app refuses root features by design; no cloud target to exfiltrate to. |
| Shoulder-surfing / screenshots | FLAG_SECURE on credential screens; secrets hidden by default. |
| Backup extraction | `allowBackup=false` + `fullBackupContent=false`. |
| Supply chain (new provider) | See PROVIDER_DEVELOPMENT.md — every provider must pass the compliance checklist, declare hosts, and redact sensitive fields. |

## 7. Reporting

This is an MVP prototype with mostly mock providers and one explicitly marked
high-risk, non-official personal MiYouShe check-in experiment. The experiment
is disabled by default, stores credentials locally, and does not implement
likes, comments, redemptions, CAPTCHA handling, or risk-control workarounds.
If you find a real security issue in a future release, report it privately to
the maintainer; do not post credentials or raw traffic in public issues.
