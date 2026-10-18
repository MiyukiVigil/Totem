//! Shared protocol code for Totem.
//!
//! - [`otp`]: HOTP (RFC 4226) / TOTP (RFC 6238) and base32 decoding.
//! - [`noise`]: Noise IK handshakes used for pairing and per-request sessions.
//! - [`relay`]: device ↔ relay messages and device ids.
//! - [`app`]: messages inside the encrypted channel, and the pairing QR code.
//!
//! The Kotlin implementation in `android/core` must stay byte-for-byte compatible with this
//! crate; both are tested against `test-vectors/` and against each other (see `scripts/interop.sh`).

pub mod app;
pub mod noise;
pub mod otp;
pub mod relay;
