# Totem

Get 2FA codes on your PC without picking up your phone. The phone keeps the TOTP secrets and
does the maths; the PC asks for a code over an end-to-end encrypted link and only ever gets the
6-digit code back.

Status: **M3, desktop app working** (tray, Ctrl+Shift+A, built-in relay). End to end, with the
phone asleep and Totem not running on it, a request gets a correct code in about 1 second:
relay → FCM wake → phone answers over Noise IK. The Android app is also a standalone
authenticator (encrypted vault, app lock, QR / manual / Google Authenticator import, encrypted
backups). Hardening (M4) is next.

## Quick start: desktop app (home network)

1. Build the installer: `cd desktop && npm install && npx tauri build`, then run
   `target/release/bundle/nsis/Totem_0.1.0_x64-setup.exe`.
2. Open Totem. It sits in the tray and runs the relay itself on port 8080. Windows asks once
   whether to allow it on networks: allow **Private** only.
3. The pairing window opens on first run: click **Show code**, then on the phone go to
   Settings → Pair with a PC and scan it.
4. Anywhere on the PC: **Ctrl+Shift+A**, type part of the name, **Enter**. The code is on the
   clipboard (kept out of Win+V history and cloud clipboard, cleared after 20 s).

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

The relay here uses plain `ws://`, which only debug builds of the app allow. That's fine on a home
network because everything inside is Noise-encrypted anyway. A hosted relay should use `wss://`.

## Layout

| Path | What |
|---|---|
| `protocol/` | Rust crate: OTP engine, Noise handshakes, relay and app message formats |
| `relay/` | Relay server (axum WebSockets, Ed25519 login, FCM wake pushes) |
| `client/` | PC-side library shared by the CLI and desktop app (keys, pairing, sessions); `examples/fake_phone.rs` stands in for a phone in tests |
| `cli/` | `totem` command: pair, list, code |
| `desktop/` | Tauri desktop app: tray, Ctrl+Shift+A search popup, pairing window, built-in relay |
| `android/core/` | Kotlin/JVM module: the same OTP engine and Noise helpers, used by the phone app |
| `android/app/` | Android app: vault, app lock, account list, scanning, backups |
| `test-vectors/` | JSON vectors (RFC 4226/6238/4648) that both test suites read |
| `scripts/interop.sh` | Runs all tests, including Kotlin ↔ Rust handshakes |
| `scripts/push-spike.mjs` | Times FCM push → phone → PC round trips (debug builds only) |

## Firebase setup (needed to build the app)

1. Create a Firebase project and add an Android app with package `tech.miyukivigil.totem`.
2. Put its `google-services.json` in `android/app/` (gitignored).
3. For `push-spike.mjs`: download a service-account key and keep it **outside the repo**
   (default location: `~/.secrets/*firebase-adminsdk*.json`).

On phones with restricted networking, allow network access (including background data) for Totem
in Android settings, or pushes and PC requests won't get through.

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
./scripts/interop.sh                                # all tests
cd android && ./gradlew :app:installDebug           # install the dev app on a connected phone
```

Debug and release builds share the ID `tech.miyukivigil.totem`, so installing one replaces the other.
