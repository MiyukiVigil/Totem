//! Checks the OTP engine against the shared vectors in `test-vectors/otp.json`.

use totem_protocol::otp::{base32_decode, hotp, totp, Algorithm};
use serde::Deserialize;

#[derive(Deserialize)]
struct Vectors {
    hotp: Vec<HotpVector>,
    totp: Vec<TotpVector>,
    base32: Vec<Base32Vector>,
}

#[derive(Deserialize)]
struct HotpVector {
    algorithm: String,
    secret_hex: String,
    secret_base32: String,
    digits: u32,
    counter: u64,
    code: String,
}

#[derive(Deserialize)]
struct TotpVector {
    algorithm: String,
    secret_hex: String,
    secret_base32: String,
    digits: u32,
    period: u64,
    time: u64,
    code: String,
}

#[derive(Deserialize)]
struct Base32Vector {
    plain_hex: String,
    base32: String,
}

fn vectors() -> Vectors {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../test-vectors/otp.json");
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

#[test]
fn hotp_matches_rfc4226() {
    for v in vectors().hotp {
        let secret = hex::decode(&v.secret_hex).unwrap();
        assert_eq!(base32_decode(&v.secret_base32).unwrap(), secret);
        let alg = Algorithm::parse(&v.algorithm).unwrap();
        assert_eq!(hotp(&secret, v.counter, v.digits, alg).unwrap(), v.code, "counter {}", v.counter);
    }
}

#[test]
fn totp_matches_rfc6238() {
    for v in vectors().totp {
        let secret = hex::decode(&v.secret_hex).unwrap();
        assert_eq!(base32_decode(&v.secret_base32).unwrap(), secret);
        let alg = Algorithm::parse(&v.algorithm).unwrap();
        assert_eq!(
            totp(&secret, v.time, v.period, v.digits, alg).unwrap(),
            v.code,
            "{} at {}",
            v.algorithm,
            v.time
        );
    }
}

#[test]
fn base32_matches_rfc4648() {
    for v in vectors().base32 {
        assert_eq!(base32_decode(&v.base32).unwrap(), hex::decode(&v.plain_hex).unwrap());
    }
}
