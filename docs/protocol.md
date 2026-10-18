# Totem protocol

How a PC gets a 2FA code from a phone without ever holding the secret. Version 1.

Three parties:

- **Phone**: holds the TOTP/HOTP secrets and computes codes.
- **PC**: asks for codes and only ever receives the 6–8 digit code.
- **Relay**: forwards messages between the two and wakes the phone with a push. It sees device ids
  and ciphertext, nothing else. It can run inside the desktop app (home network), on Cloudflare
  (`relay-worker/`), or as a standalone server (`relay/`).

All three implementations are tested against each other: `relay/tests/relay.rs` and
`client/tests/e2e.rs` run against any relay with `TOTEM_TEST_RELAY_URL`, and the Kotlin tests run
handshakes against the Rust implementation (`protocol/examples/noise_peer.rs`).

## 1. Identities

Each device has two long-term key pairs, created on first run:

| Key | Algorithm | Used for | Stored |
|---|---|---|---|
| Relay key | Ed25519 | Logging in to the relay | Phone: `device.bin` (Keystore-encrypted). PC: Windows Credential Manager |
| Noise key | X25519 | End-to-end encryption | Same |

A device's **relay id** is `hex(SHA-256(ed25519_public)[0..16])`: 32 hex characters.

## 2. Relay protocol

WebSocket at `/v1/ws`, one JSON object per text frame. Plain `ws://` is accepted only for
local-network hosts (private IPv4, IPv6 ULA / link-local, loopback, `*.local`). Anything else must be
`wss://`. Both clients enforce this (`check_relay_url` / `RelayProtocol.checkRelayUrl`).

```text
relay  -> device  {"t":"challenge","nonce":<base64, 32 random bytes>}
device -> relay   {"t":"auth","pubkey":<base64 Ed25519 public key>,"sig":<base64 signature>}
relay  -> device  {"t":"ready","id":<relay id>}
device -> relay   {"t":"register_push","fcm":<FCM token>}              phone only
device -> relay   {"t":"allow","peers":[<relay id>, ...]}              phone only
device -> relay   {"t":"send","to":<relay id>,"sid":<session id>,"data":<base64>}
relay  -> device  {"t":"msg","from":<relay id>,"sid":<session id>,"data":<base64>}
relay  -> device  {"t":"error","code":<code>,"msg":<text>}
```

- **Login.** The device signs `"totem-relay-auth-v1" || nonce`. A device that doesn't log in within 10 s is
  disconnected. A newer login for the same id replaces the older connection.
- **Allow list.** Once a phone sends `allow`, the relay only forwards messages to it from those
  peers (`not_allowed` otherwise). PCs never send one; they answer only valid Noise handshakes anyway.
- **Offline phones.** A message for a device that isn't connected is held for 15 s (at most 32 per
  device). If the device registered an FCM token, the relay sends a high-priority data push
  `{"kind":"wake"}`, at most one every 2 s per device. The phone connects and gets the held messages
  right after `ready`.
- **Limits.** `data` up to 16 KiB of base64, 60 messages per 10 s per connection (`too_large`,
  `queue_full`, `rate_limited`).

The relay stores only FCM tokens and allow lists.

## 3. Pairing

The PC shows a QR code:

```text
totem://pair?v=1&relay=<url>&id=<PC relay id>&k=<PC Noise public key, base64url>&s=<32-byte secret, base64url>&exp=<unix time>
```

It expires after 3 minutes. The phone scans it and runs **`Noise_IK_25519_ChaChaPoly_SHA256`**
towards `k`, with prologue `"Totem/1 pairing"`:

```text
phone -> PC   IK message 1, payload PairingHello {"secret": <base64>, "phone_id": <relay id>, "name": <phone name>}
PC -> phone   IK message 2, payload PairingWelcome {"name": <PC name>}
phone -> PC   transport message: PairingDone {"accepted": true | false}
```

- The PC checks `secret` in constant time and ignores handshakes with the wrong one.
- Both screens show a **6-digit confirmation code**: the first 4 bytes of the handshake hash, as
  a big-endian number mod 10⁶. The user compares them on the phone. The phone sends
  `accepted`, and the PC saves the pairing only on `true`.
- Afterwards the phone stores the PC's relay id, Noise key, name and relay URL. The PC stores the
  phone's relay id and Noise key, which it learned in the handshake.

IK with the secret as payload stands in for `XXpsk3`: Signal's noise-java on Android has no
`psk` support. The secret proves the phone saw the QR code, and IK's first message is already
encrypted to the PC's static key, so the relay never sees the secret.

## 4. Sessions

For each burst of requests the PC opens a session: a fresh random `sid` and an IK handshake towards
the phone's Noise key, with prologue `"Totem/1 session"` and empty payloads. The phone accepts only
if the PC's static key equals the one saved at pairing. It ignores the session otherwise and warns
the user, because something else is using that PC's relay identity. Then every request is one
transport message and gets one reply. The phone ends a session after 8 s of silence.

Requests (JSON inside the Noise transport):

```text
PC -> phone   {"type":"list_accounts","req":<id>,"ts":<unix>}
PC -> phone   {"type":"get_code","req":<id>,"ts":<unix>,"account":<account id>}
phone -> PC   {"type":"accounts","req":..,"items":[{"id","issuer","label","policy":"auto"|"ask"|"never"}]}
phone -> PC   {"type":"code","req":..,"code":"123456","valid_until":<unix>,"next_code":<only near expiry>}
phone -> PC   {"type":"denied","req":..,"reason":<reason>}
```

- **Freshness.** The phone refuses requests with `ts` more than 120 s off its own clock
  (`clock_skew`), and repeated `req` ids (`replayed`).
- **Policy, per account.** `AUTO` answers straight away. `ASK` shows an Approve / Deny notification,
  and Approve needs the phone unlocked. `NEVER` answers `policy_never`.
- **Burst guard.** If a PC got 10 codes in the last 5 minutes, the phone pauses it for 15 minutes
  (`paused`) and warns the user. This is checked against the on-phone activity log, so it survives
  restarts.
- **Next code.** With under 5 s left on a TOTP code, the reply also carries the next one.
  HOTP replies move the counter on.

Reasons: `user_denied`, `approval_timeout`, `policy_never`, `paused`, `unknown_account`,
`clock_skew`, `replayed`.

## 5. Home-network discovery

The desktop app announces its built-in relay over mDNS as `_totem._tcp.local.`, with TXT `id=<PC
relay id>` and `path=/v1/ws`. If a `ws://` relay stops answering, for example because the PC got a
new address, the phone browses for an announcement whose `id` belongs to a PC paired on that
relay. It connects there and saves the new address. The desktop app reaches its own relay over
`127.0.0.1`.

A fake announcement can at most redirect the phone to another local relay. It can't impersonate
the PC (Noise), and it can't move the phone off the local network (`ws://` rule).

## 6. Files on the phone

| File | Contents | Key |
|---|---|---|
| `vault.bin` | Accounts | Keystore AES-256-GCM, usable while locked |
| `sealed.bin` | Phone-only accounts, if the extra lock is on | Keystore AES-256-GCM, usable for 5 min after unlock |
| `device.bin` | Relay + Noise keys, paired PCs, auto-forget setting | Same key as `vault.bin` |
| `activity.bin` | Last 500 PC events | Same key as `vault.bin` |

Each file uses its own associated data (`totem-vault-v1`, `totem-sealed-v1`, `totem-device-v1`,
`totem-activity-v1`), so one can't be swapped for another.

Backups (`.totem`): `"TOTEMBK" | version | Argon2id params | salt | nonce | AES-256-GCM`, with the
whole header as associated data. See `android/core/.../Backup.kt`.
