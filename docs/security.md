# Totem security review

What Totem protects, against whom, and where the limits are. Reviewed 2026-10-18 for app 0.3.0 /
desktop 0.2.0. The checklist in part 3 loosely follows OWASP MASVS v2 for the phone app. The wire
format is in [protocol.md](protocol.md).

## 1. The idea in one paragraph

A 2FA code is only useful together with the password, and only for about 30 seconds. Totem keeps
the long-lived **secrets** on the phone and sends the PC only the short-lived **code**. So someone
who breaks into the PC, the relay or the network can, at worst, get codes while they're there
(within the limits below), never the secrets.

## 2. Threats

| Who | What they could try | What stops them | What's left |
|---|---|---|---|
| **Relay operator** (Cloudflare, a server host, the desktop app) | Read or change codes | Noise IK end to end. The relay only sees ciphertext | Metadata: which device ids talk, when, and message sizes |
| **Network attacker** | Same, or pose as the relay | Noise. `wss://` required outside the home network | On the home network: blocking traffic, or a fake mDNS announcement steering the phone to another local relay (denial of service only) |
| **Google (FCM)** | Learn something from pushes | Pushes are empty (`kind: wake`) | Timing of wake-ups |
| **Someone at your unlocked PC** | Press Ctrl+Shift+A and copy codes | Windows Hello before copying (on by default, valid 10 min). Approve / Deny on the phone for "Ask first" accounts. Phone-only accounts never leave the phone | "Auto" accounts within 10 minutes of a Hello check |
| **Malware on the PC** (running as you) | Use Totem's keys from Credential Manager to request codes; read the clipboard | "Ask first" needs your approval on the phone. Phone-only is refused. The burst guard pauses a PC after 10 codes in 5 min, and the phone shows a notification for every code sent. The clipboard is kept out of Win+V history and cloud sync and cleared after 20 s | Malware can skip the desktop app (and Windows Hello) and request **Auto** accounts directly, up to the burst limit. **Use "Ask first" for accounts that matter.** It can also read a code while it's on the clipboard |
| **Copied PC keys** (backup, stolen disk) | Pose as your PC from elsewhere | Same as above. Remove the PC on the phone and it can't do anything. Unused PCs are forgotten after 90 days (configurable) | Until removed: as malware |
| **Someone with your phone, locked** | Read accounts or approve requests | Totem's app lock (biometric / screen lock). Approve needs the phone unlocked. Lock-screen notifications hide account and PC names | Deny works from the lock screen (by design) |
| **Someone with your phone, unlocked** | Open Totem | App lock again (asks on launch and after 30 s in the background) | Whatever your screen lock allows |
| **Malicious app on the phone** | Read Totem's files, screenshot codes, trigger approvals | Android sandbox. Files are AES-GCM with a non-exportable Keystore key (TEE here; StrongBox where available). FLAG_SECURE on every screen. Only the launcher activity is exported; the approval receiver isn't | Nothing without a root exploit |
| **Root / compromised Android** | Use the Keystore key from inside Totem's process | Phone-only accounts with the **extra lock** need an unlock in the last 5 minutes. Everything else is usable by Totem in the background, by design | Auto and Ask accounts, and with the extra lock off, phone-only ones too |
| **Backups** | Brute-force an exported `.totem` file | Argon2id (64 MiB, 3 passes) + AES-256-GCM. Minimum 8-character password | A weak password |
| **Replays** | Resend captured requests | A fresh Noise session per burst (an old session can't be continued), `ts` within ±120 s, repeated `req` ids refused | none known |

## 3. Phone app checklist

| Area | Check | Status |
|---|---|---|
| Storage | Secrets only in app-private files, encrypted with Keystore AES-256-GCM | ✅ `vault.bin`, `sealed.bin`, `device.bin`, `activity.bin` |
| Storage | Nothing in logs | ✅ `Account.toString` hides the secret. The FCM token is logged in debug builds only |
| Storage | Excluded from cloud backup and device transfer | ✅ `allowBackup=false` plus data extraction rules |
| Storage | No secrets in screenshots or the recents preview | ✅ FLAG_SECURE (debug builds can turn it off for testing) |
| Storage | Clipboard | ✅ The phone never copies codes. The PC copies with history / cloud exclusion and clears after 20 s |
| Crypto | Standard algorithms only | ✅ AES-GCM, X25519 / ChaCha20-Poly1305 / SHA-256 (Noise), Ed25519, Argon2id, HMAC-SHA1/256/512 (TOTP) |
| Crypto | Random nonces never repeat under one key | ✅ Keystore picks the GCM IVs. Backups use a fresh salt and key each time |
| Auth | App lock with strong biometric or screen lock | ✅ BiometricPrompt, `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` |
| Auth | Sensitive actions re-check the user | ✅ Approve needs unlock (`setAuthenticationRequired`). The extra lock needs an unlock in the last 5 min |
| Network | TLS for internet traffic | ✅ `ws://` only to local addresses, enforced in code on both ends |
| Network | End-to-end encryption independent of TLS | ✅ Noise IK, static keys pinned at pairing |
| Platform | Minimal exported components | ✅ Launcher activity only (debug builds add a deep link for tests) |
| Platform | Deep links can't start pairing in release | ✅ The deep link exists only in the debug manifest |
| Platform | Permissions | INTERNET, CAMERA (scanning), FOREGROUND_SERVICE, POST_NOTIFICATIONS |
| Code | Release build not debuggable, minified | ✅ R8 on. `run-as` refused on the phone |
| Code | Signed with a key kept off the repo | ✅ v3 key rotation from the original debug key. Keys in `~/.secrets` |
| Resilience | Root / tamper detection | ❌ Not done, on purpose: easy to bypass, and it would lock out the owner |
| Privacy | Activity history stays on the phone | ✅ The last 500 events, encrypted, can be cleared |

## 4. Known gaps and ideas

1. **Auto accounts and PC malware** (see the table). Possible next step: require the phone's
   approval the first time each day per PC, or for any account after an alert.
2. **The desktop app's own keys** sit in Windows Credential Manager, readable by anything running
   as you. Binding them to Windows Hello (Microsoft Passport / TPM keys) would stop silent use. It's a
   larger change, because the relay login and Noise both need the private keys.
3. **The replay list** lives in memory and is lost when the app restarts. That's harmless today:
   a replay needs the old session's keys, and a new process has none.
4. **mDNS** can be spoofed on the home network. The impact is denial of service only (see the protocol doc).
5. **Not audited by a third party.** This is a personal project. The crypto comes from mature
   libraries (snow, noise-java, BouncyCastle, Android Keystore), but the glue is ours.
