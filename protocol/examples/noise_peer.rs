//! One side of a Noise handshake, driven over stdin/stdout, for interop tests with the Kotlin code.
//!
//! ```text
//! noise_peer --mode pairing|session --role initiator|responder --private <hex>
//!            [--remote <hex>] [--secret <hex>] [--expect-remote <hex>]
//! ```
//! - pairing initiator (phone): `--remote` = PC public key, `--secret` = pairing secret to send
//! - pairing responder (PC):    `--secret` = pairing secret to expect
//! - session initiator (PC):    `--remote` = phone public key
//! - session responder (phone): optional `--expect-remote` = paired PC public key
//!
//! Line protocol (one item per line):
//! - `MSG <hex>`    a Noise message, in either direction
//! - `HASH <hex>`   printed once the handshake finishes
//! - `REMOTE <hex>` the peer's static public key, printed after `HASH`
//! - `PLAIN <text>` a decrypted transport payload
//! - `ERR <text>`   printed before exiting with status 1
//!
//! After the handshake the initiator sends `ping from rust`; the responder answers with
//! `pong: <what it received>`.

use totem_protocol::noise::{self, MAX_MESSAGE_LEN};
use std::collections::HashMap;
use std::io::{self, BufRead, Write};

fn main() {
    if let Err(e) = run() {
        println!("ERR {e}");
        io::stdout().flush().ok();
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let args = parse_args()?;
    let get = |k: &str| args.get(k).ok_or(format!("missing --{k}"));
    let hex_arg = |k: &str| -> Result<Vec<u8>, String> { hex::decode(get(k)?).map_err(|e| format!("--{k}: {e}")) };

    let private = hex_arg("private")?;
    let pairing = match get("mode")?.as_str() {
        "pairing" => true,
        "session" => false,
        m => return Err(format!("unknown mode {m}")),
    };
    let initiator = match get("role")?.as_str() {
        "initiator" => true,
        "responder" => false,
        r => return Err(format!("unknown role {r}")),
    };
    let mut hs = match (pairing, initiator) {
        (true, true) => noise::pairing_initiator(&private, &hex_arg("remote")?),
        (true, false) => noise::pairing_responder(&private),
        (false, true) => noise::session_initiator(&private, &hex_arg("remote")?),
        (false, false) => noise::session_responder(&private),
    }
    .map_err(|e| e.to_string())?;

    let stdin = io::stdin();
    let mut lines = stdin.lock().lines();
    let mut read_msg = || -> Result<Vec<u8>, String> {
        let line = lines.next().ok_or("stdin closed")?.map_err(|e| e.to_string())?;
        let hex = line.strip_prefix("MSG ").ok_or(format!("expected MSG, got {line:?}"))?;
        hex::decode(hex.trim()).map_err(|e| e.to_string())
    };
    let send = |msg: &[u8]| {
        println!("MSG {}", hex::encode(msg));
        io::stdout().flush().ok();
    };

    // IK is two messages: initiator -> responder (carries the pairing secret when pairing),
    // then responder -> initiator.
    let mut buf = vec![0u8; MAX_MESSAGE_LEN];
    let mut payload = vec![0u8; MAX_MESSAGE_LEN];
    if initiator {
        let first = if pairing { hex_arg("secret")? } else { Vec::new() };
        let n = hs.write_message(&first, &mut buf).map_err(|e| format!("write handshake: {e}"))?;
        send(&buf[..n]);
        hs.read_message(&read_msg()?, &mut payload).map_err(|e| format!("read handshake: {e}"))?;
    } else {
        let p = hs.read_message(&read_msg()?, &mut payload).map_err(|e| format!("read handshake: {e}"))?;
        if pairing && !noise::pairing_secret_matches(&hex_arg("secret")?, &payload[..p]) {
            return Err("pairing secret does not match".into());
        }
        let n = hs.write_message(&[], &mut buf).map_err(|e| format!("write handshake: {e}"))?;
        send(&buf[..n]);
    }

    let remote = hs.get_remote_static().ok_or("no remote static key")?.to_vec();
    if let Some(expected) = args.get("expect-remote") {
        if hex::encode(&remote) != expected.to_lowercase() {
            return Err("remote static key is not the expected one".into());
        }
    }
    println!("HASH {}", hex::encode(hs.get_handshake_hash()));
    println!("REMOTE {}", hex::encode(&remote));
    io::stdout().flush().ok();

    let mut t = hs.into_transport_mode().map_err(|e| e.to_string())?;
    let mut decrypt = |msg: &[u8], t: &mut noise::TransportState| -> Result<String, String> {
        let n = t.read_message(msg, &mut payload).map_err(|e| format!("decrypt: {e}"))?;
        Ok(String::from_utf8_lossy(&payload[..n]).into_owned())
    };
    if initiator {
        let n = t.write_message(b"ping from rust", &mut buf).map_err(|e| e.to_string())?;
        send(&buf[..n]);
        let reply = decrypt(&read_msg()?, &mut t)?;
        println!("PLAIN {reply}");
    } else {
        let got = decrypt(&read_msg()?, &mut t)?;
        println!("PLAIN {got}");
        let n = t.write_message(format!("pong: {got}").as_bytes(), &mut buf).map_err(|e| e.to_string())?;
        send(&buf[..n]);
    }
    io::stdout().flush().ok();
    Ok(())
}

fn parse_args() -> Result<HashMap<String, String>, String> {
    let mut map = HashMap::new();
    let mut it = std::env::args().skip(1);
    while let Some(k) = it.next() {
        let key = k.strip_prefix("--").ok_or(format!("unexpected argument {k}"))?.to_string();
        let value = it.next().ok_or(format!("--{key} needs a value"))?;
        map.insert(key, value);
    }
    Ok(map)
}
