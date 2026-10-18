//! Shared protocol code for Totem.
//!
//! - [`otp`]: HOTP (RFC 4226) / TOTP (RFC 6238) and base32 decoding.
//! - [`noise`]: Noise IK handshakes used for pairing and per-request sessions.
//!
//! The Kotlin implementation in `android/core` must stay byte-for-byte compatible with this
//! crate; both are tested against `test-vectors/` and against each other (see `scripts/interop.sh`).

pub mod noise;
pub mod otp;
