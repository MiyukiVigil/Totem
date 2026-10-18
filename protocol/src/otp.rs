//! HOTP (RFC 4226) and TOTP (RFC 6238).

use hmac::{Hmac, Mac};
use sha1::Sha1;
use sha2::{Sha256, Sha512};
use thiserror::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Algorithm {
    Sha1,
    Sha256,
    Sha512,
}

impl Algorithm {
    /// Parses the `algorithm` value used in `otpauth://` URIs and the test vectors.
    pub fn parse(s: &str) -> Result<Self, OtpError> {
        match s.to_ascii_uppercase().as_str() {
            "SHA1" => Ok(Self::Sha1),
            "SHA256" => Ok(Self::Sha256),
            "SHA512" => Ok(Self::Sha512),
            _ => Err(OtpError::UnknownAlgorithm(s.to_string())),
        }
    }
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum OtpError {
    #[error("digits must be between 6 and 10, got {0}")]
    InvalidDigits(u32),
    #[error("period must be greater than zero")]
    InvalidPeriod,
    #[error("secret must not be empty")]
    EmptySecret,
    #[error("unknown algorithm: {0}")]
    UnknownAlgorithm(String),
    #[error("invalid base32 character: {0:?}")]
    InvalidBase32(char),
}

fn hmac(alg: Algorithm, key: &[u8], msg: &[u8]) -> Vec<u8> {
    // new_from_slice only fails for fixed-size keys, which HMAC does not have.
    match alg {
        Algorithm::Sha1 => {
            let mut m = Hmac::<Sha1>::new_from_slice(key).expect("HMAC accepts any key length");
            m.update(msg);
            m.finalize().into_bytes().to_vec()
        }
        Algorithm::Sha256 => {
            let mut m = Hmac::<Sha256>::new_from_slice(key).expect("HMAC accepts any key length");
            m.update(msg);
            m.finalize().into_bytes().to_vec()
        }
        Algorithm::Sha512 => {
            let mut m = Hmac::<Sha512>::new_from_slice(key).expect("HMAC accepts any key length");
            m.update(msg);
            m.finalize().into_bytes().to_vec()
        }
    }
}

/// RFC 4226 HOTP value for `counter`, zero-padded to `digits`.
pub fn hotp(secret: &[u8], counter: u64, digits: u32, alg: Algorithm) -> Result<String, OtpError> {
    if !(6..=10).contains(&digits) {
        return Err(OtpError::InvalidDigits(digits));
    }
    if secret.is_empty() {
        return Err(OtpError::EmptySecret);
    }
    let mac = hmac(alg, secret, &counter.to_be_bytes());
    // Dynamic truncation (RFC 4226 section 5.3).
    let offset = (mac[mac.len() - 1] & 0x0f) as usize;
    let binary = ((mac[offset] as u64 & 0x7f) << 24)
        | ((mac[offset + 1] as u64) << 16)
        | ((mac[offset + 2] as u64) << 8)
        | (mac[offset + 3] as u64);
    let code = binary % 10u64.pow(digits);
    Ok(format!("{:0width$}", code, width = digits as usize))
}

/// RFC 6238 TOTP value at `unix_time` (seconds).
pub fn totp(
    secret: &[u8],
    unix_time: u64,
    period: u64,
    digits: u32,
    alg: Algorithm,
) -> Result<String, OtpError> {
    if period == 0 {
        return Err(OtpError::InvalidPeriod);
    }
    hotp(secret, unix_time / period, digits, alg)
}

/// Seconds until the code at `unix_time` stops being valid.
pub fn seconds_remaining(unix_time: u64, period: u64) -> u64 {
    period - (unix_time % period)
}

/// Decodes RFC 4648 base32 as found in `otpauth://` secrets: case-insensitive,
/// spaces/dashes ignored, `=` padding optional.
pub fn base32_decode(input: &str) -> Result<Vec<u8>, OtpError> {
    let mut out = Vec::with_capacity(input.len() * 5 / 8);
    let mut buffer: u32 = 0;
    let mut bits = 0u32;
    for c in input.chars() {
        let value = match c {
            'A'..='Z' => c as u32 - 'A' as u32,
            'a'..='z' => c as u32 - 'a' as u32,
            '2'..='7' => c as u32 - '2' as u32 + 26,
            ' ' | '-' | '=' => continue,
            _ => return Err(OtpError::InvalidBase32(c)),
        };
        buffer = (buffer << 5) | value;
        bits += 5;
        if bits >= 8 {
            bits -= 8;
            out.push((buffer >> bits) as u8);
            buffer &= (1 << bits) - 1;
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_bad_parameters() {
        assert_eq!(hotp(b"k", 0, 5, Algorithm::Sha1), Err(OtpError::InvalidDigits(5)));
        assert_eq!(hotp(b"", 0, 6, Algorithm::Sha1), Err(OtpError::EmptySecret));
        assert_eq!(totp(b"k", 0, 0, 6, Algorithm::Sha1), Err(OtpError::InvalidPeriod));
        assert!(base32_decode("ABC1").is_err());
    }

    #[test]
    fn base32_is_lenient_about_formatting() {
        assert_eq!(base32_decode("mzxw 6ytb-oi==").unwrap(), b"foobar");
    }

    #[test]
    fn seconds_remaining_counts_down() {
        assert_eq!(seconds_remaining(0, 30), 30);
        assert_eq!(seconds_remaining(29, 30), 1);
        assert_eq!(seconds_remaining(30, 30), 30);
    }
}
