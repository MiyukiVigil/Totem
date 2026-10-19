# Totem

> [!NOTE]
> **This is a personal project.** I built it for my own phone and PC and share it as is. I don't
> offer support, take feature requests, publish builds or promise updates. If you want to use it,
> **fork it** and run your own copy: create your own Firebase project, signing keys and relay,
> and maintain it yourself. Read [docs/security.md](docs/security.md) first; it hasn't been
> audited by anyone else.

Get 2FA codes on your PC without picking up your phone. The phone keeps the TOTP secrets and
does the maths; the PC asks for a code over an end-to-end encrypted link and only ever gets the
6-digit code back.

Status: **in daily use** (phone app 0.4.0, desktop 0.4.0, browser extension 0.1.0). With the phone asleep and Totem not
running on it, a request gets a correct code in about 1 second: relay → FCM wake → phone answers
over Noise IK. The Android app is also a standalone authenticator: encrypted vault, app lock,
QR / manual / Google Authenticator / Aegis / 2FAS import, and encrypted backups. Accounts show
their brand logo (3,300+ bundled offline from Simple Icons) on the phone and the PC.

Security features:
- Per-account policy: Auto, Ask first (Approve / Deny on the phone) or Phone only.
- Daily check-in: the first code each day per PC needs Approve on the phone.
- Optional key lock: the PC's keys are encrypted with Windows Hello.
- Windows Hello before copying on the PC.
- An activity log on the phone. A PC asking for codes in a burst gets paused, and you get warnings.
- Unused PCs are forgotten automatically.
- An optional extra lock for phone-only accounts.

[docs/protocol.md](docs/protocol.md) describes the protocol, and [docs/security.md](docs/security.md)
covers what it does and doesn't protect against.

## Quick start: desktop app (home network)

1. Build the installer: `cd desktop && npm install && npx tauri build`, then run
   `target/release/bundle/nsis/Totem_<version>_x64-setup.exe`.
2. Open Totem. It sits in the tray and runs the relay itself on port 8080. Windows asks once
   whether to allow it on networks: allow **Private** only.
3. The pairing window opens on first run: click **Show code**, then on the phone go to
   Settings → Pair with a PC and scan it.
4. Anywhere on the PC: **Ctrl+Shift+A**, type part of the name, **Enter**. Windows Hello asks
   who you are (at most every 10 minutes), then the code is on the clipboard. It's kept out of
   Win+V history and cloud clipboard and cleared after 20 s. **Shift+Enter** types the code
   into the window underneath instead, so it never touches the clipboard.

The tray menu has switches for Windows Hello, **Lock this PC's keys with Windows Hello**, and
**Start with Windows**. If the PC gets a new
address on the network, the phone finds it again automatically (mDNS).

**In the browser:** the Totem extension for Chrome, Edge and Brave fills codes straight into
2FA boxes. Click the Totem button in the box, or the toolbar button (**Alt+Shift+T**). See
[browser-extension/README.md](browser-extension/README.md).

**Away from home:** run the relay on Cloudflare instead, free and always on. See
[relay-worker/README.md](relay-worker/README.md). To run it on a server of your own instead, build
`totem-relay` (below), run it with `--listen 0.0.0.0:8080 --store <file> --fcm-key <key.json>`,
and put it behind a TLS proxy (Caddy, nginx, Cloudflare Tunnel): phones and PCs only accept
`wss://` for addresses outside the home network.

Keys live in Windows Credential Manager (`Totem` / `pc-keys`); settings in `%APPDATA%\Totem`.
The relay wakes the phone using the Firebase key it finds in `~/.secrets` (or `TOTEM_FCM_KEY`).

## Quick start: command line

```sh
cargo build --release -p totem-relay -p totem-cli

# 1. Run the relay. Windows will ask once whether to allow it on private networks: allow it.
./target/release/totem-relay --listen 0.0.0.0:8080 --fcm-key ~/.secrets/<firebase-adminsdk>.json

# 2. Pair (use your PC's LAN address). Then on the phone: Settings → Pair with a PC → scan.
./target/release/totem pair --relay ws://192.168.0.102:8080/v1/ws

# 3. Use it.
./target/release/totem list
./target/release/totem code github      # copies to the clipboard, clears it after 20 s
```

The relay here uses plain `ws://`, which the app accepts only for local-network addresses. That's
fine on a home network because everything inside is Noise-encrypted anyway. A relay on the
internet must use `wss://`.

## Layout

| Path | What |
|---|---|
| `protocol/` | Rust crate: OTP engine, Noise handshakes, relay and app message formats |
| `relay/` | Relay server (axum WebSockets, Ed25519 login, FCM wake pushes) |
| `relay-worker/` | The same relay as a Cloudflare Worker + Durable Object |
| `docs/` | Protocol and security review |
| `client/` | PC-side library shared by the CLI and desktop app (keys, pairing, sessions); `examples/fake_phone.rs` stands in for a phone in tests |
| `cli/` | `totem` command: pair, list, code |
| `desktop/` | Tauri desktop app: tray, Ctrl+Shift+A search popup, pairing window, built-in relay, browser extension bridge |
| `browser-extension/` | Chrome / Edge / Brave extension that fills codes through the desktop app (`node --test browser-extension/`) |
| `android/core/` | Kotlin/JVM module: the same OTP engine and Noise helpers, used by the phone app |
| `android/app/` | Android app: vault, app lock, account list, scanning, backups |
| `test-vectors/` | JSON vectors (RFC 4226/6238/4648) that both test suites read |
| `scripts/interop.sh` | Runs all tests, including Kotlin ↔ Rust handshakes |
| `scripts/push-spike.mjs` | Times FCM push → phone → PC round trips (debug builds only) |
| `scripts/sign-android.ps1` | Signs release APKs (key rotation, see below) |
| `scripts/fetch-import-samples.sh` | Downloads real Aegis / 2FAS export samples for the import tests |
| `scripts/brand-icons.mjs` | Rebuilds the bundled brand logos (`brand-icons.json`) from Simple Icons |
| `scripts/android-icons.mjs` | Generates the Android icons from `desktop/icon.svg`'s shapes |

## Firebase setup (needed to build the app)

1. Create a Firebase project and add an Android app with package `tech.miyukivigil.totem`.
2. Put its `google-services.json` in `android/app/` (gitignored).
3. For `push-spike.mjs`: download a service-account key and keep it **outside the repo**
   (default location: `~/.secrets/*firebase-adminsdk*.json`).

On phones with restricted networking, allow network access (including background data) for Totem
in Android settings, or pushes and PC requests won't get through.

## Android release builds (what's on the phone)

The phone runs a **release** build: not debuggable, R8-shrunk, screenshots always blocked, no adb
test switches. It's signed with a key in `~/.secrets` using APK Signature Scheme v3 **key
rotation** from the PC's original debug key, so it installed as an update without losing data.

```sh
cd android && ./gradlew :app:assembleRelease
powershell -File scripts/sign-android.ps1        # → android/app/build/outputs/apk/release/totem-<version>.apk
adb install -r android/app/build/outputs/apk/release/totem-<version>.apk
```

Bump `versionCode` for each release. `~/.secrets` must contain `totem-release.jks`,
`totem-release.properties`, `totem-original-debug.keystore` and `totem-lineage.bin`. **Back the
whole folder up.** Without it, updates need backup → uninstall → reinstall → import.

Debug builds can no longer be installed over the release (rollback is disabled in the lineage).
To try a debug build, use an emulator or a second device.

Relay addresses: `ws://` is accepted only for local-network hosts (private IPs, `*.local`);
anything else must be `wss://`. Enforced in both the app and the PC client.

## Debug-build test switches

Debug builds check for these files, created with
`adb shell run-as tech.miyukivigil.totem touch files/<name>`; release builds ignore them:

- `debug_skip_lock`: skip the fingerprint/PIN prompt (for driving the UI over adb)
- `debug_allow_screenshots`: drop FLAG_SECURE

Remove them afterwards with `run-as … rm files/<name>`.

Debug builds also accept pairing links over adb instead of the camera (release builds never do,
so a web link can't start pairing):
`adb shell "am start -a android.intent.action.VIEW -d '<totem://pair?... link>'"`.
Run `totem pair` with `TOTEM_SHOW_PAIRING_URI=1` to print the link.



## Protocol in brief

Everything uses `Noise_IK_25519_ChaChaPoly_SHA256`.

- **Pairing:** the PC shows a QR code with its public key and a one-time 32-byte secret. The
  phone runs IK towards that key and sends the secret, encrypted, in the first message. Both
  sides can show a 6-digit confirmation code taken from the handshake hash.
- **Session:** the PC runs IK towards the phone's key (learned at pairing) for each burst of
  requests. The phone only answers PCs on its paired list.
- Pairing and sessions use different prologues (`Totem/1 pairing`, `Totem/1 session`).

Signal's noise-java (used on Android) does not support `psk` modifiers, which is why pairing uses
IK with the secret as payload instead of `XXpsk3`.

## Building

Needs Rust (stable), JDK 17+ (`JAVA_HOME`) and the Android SDK (`android/local.properties`).

```sh
./scripts/interop.sh      # every test: Rust, Kotlin core, vault (Robolectric), Kotlin ↔ Rust handshakes
```

Debug and release builds share the ID `tech.miyukivigil.totem`, and a phone running the release
build refuses debug builds (see above). Try debug builds on an emulator or a second device.
