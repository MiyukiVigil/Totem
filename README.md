# Totem

Get 2FA codes on your PC without picking up your phone. The phone keeps the TOTP secrets and
does the maths; the PC asks for a code over an end-to-end encrypted link and only ever gets the
6-digit code back.

Status: **M0 (spikes)**. The OTP engine and the Noise handshake work in Rust and Kotlin and are
tested against each other. No relay, PC client or real vault yet.

## Layout

| Path | What |
|---|---|
| `protocol/` | Rust crate: OTP engine and Noise handshake helpers, shared by the PC client and relay |
| `android/core/` | Kotlin/JVM module: the same OTP engine and Noise helpers, used by the phone app |
| `android/app/` | Android app (currently an M0 screen showing codes for RFC test secrets) |
| `test-vectors/` | JSON vectors (RFC 4226/6238/4648) that both test suites read |
| `scripts/interop.sh` | Runs all tests, including Kotlin ↔ Rust handshakes |

`desktop/` (Tauri PC client) and `relay/` arrive in M2/M3.

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

Dev builds install as `tech.miyukivigil.totem.dev`, alongside any release build.
