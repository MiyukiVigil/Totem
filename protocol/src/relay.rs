//! Messages between a device and the relay (WebSocket text frames, JSON).
//!
//! ```text
//! relay  -> device  {"t":"challenge","nonce":<b64 32 bytes>}
//! device -> relay   {"t":"auth","pubkey":<b64 ed25519 public key>,"sig":<b64 signature of auth_message(nonce)>}
//! relay  -> device  {"t":"ready","id":<device id>}
//! device -> relay   {"t":"register_push","fcm":<FCM token>}         (phone)
//! device -> relay   {"t":"allow","peers":[<device id>, ...]}         (phone: who may message it)
//! device -> relay   {"t":"send","to":<device id>,"sid":<session id>,"data":<b64 Noise message>}
//! relay  -> device  {"t":"msg","from":<device id>,"sid":<session id>,"data":<b64 Noise message>}
//! relay  -> device  {"t":"error","code":<code>,"msg":<text>}
//! ```
//!
//! The relay only ever sees device ids, session ids and Noise ciphertext. A device that has sent
//! an `allow` list only receives messages from those peers; one that never did (a PC) accepts
//! from anyone, which is harmless because it only answers valid Noise handshakes.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const AUTH_CONTEXT: &[u8] = b"totem-relay-auth-v1";
/// Largest `data` the relay forwards (base64 characters). Noise messages here are tiny.
pub const MAX_DATA_LEN: usize = 16 * 1024;

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum ServerMsg {
    Challenge { nonce: String },
    Ready { id: String },
    Msg { from: String, sid: String, data: String },
    Error { code: String, msg: String },
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum ClientMsg {
    Auth { pubkey: String, sig: String },
    RegisterPush { fcm: String },
    Allow { peers: Vec<String> },
    Send { to: String, sid: String, data: String },
}

/// Checks a relay URL: `wss://` anywhere, plain `ws://` only to addresses on the local network
/// (private IPv4 ranges, IPv6 unique-local / link-local, loopback, `*.local`). Everything inside
/// is Noise-encrypted either way; this keeps internet traffic on TLS, where networks expect it
/// and metadata stays hidden. Mirrored in Kotlin (`RelayProtocol.checkRelayUrl`); keep in sync.
pub fn check_relay_url(url: &str) -> Result<(), String> {
    let lower = url.trim().to_ascii_lowercase();
    let (secure, rest) = if let Some(r) = lower.strip_prefix("wss://") {
        (true, r)
    } else if let Some(r) = lower.strip_prefix("ws://") {
        (false, r)
    } else {
        return Err("the relay address must start with ws:// or wss://".into());
    };
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    let host = if let Some(v6) = authority.strip_prefix('[') {
        v6.split(']').next().unwrap_or("")
    } else {
        authority.rsplit_once(':').map_or(authority, |(h, _)| h)
    };
    if host.is_empty() {
        return Err("the relay address has no host".into());
    }
    if secure || is_local_host(host) {
        Ok(())
    } else {
        Err(format!("{host} isn't on your local network, so the relay address must use wss:// (encrypted)"))
    }
}

fn is_local_host(host: &str) -> bool {
    if host == "localhost" || host.ends_with(".local") {
        return true;
    }
    match host.parse::<std::net::IpAddr>() {
        Ok(std::net::IpAddr::V4(ip)) => ip.is_private() || ip.is_loopback() || ip.is_link_local(),
        Ok(std::net::IpAddr::V6(ip)) => {
            let first = ip.segments()[0];
            ip.is_loopback() || (first & 0xfe00) == 0xfc00 || (first & 0xffc0) == 0xfe80
        }
        Err(_) => false,
    }
}

/// A device's relay address: first 16 bytes of SHA-256 of its Ed25519 public key, as hex.
pub fn device_id(ed25519_public: &[u8]) -> String {
    hex::encode(&Sha256::digest(ed25519_public)[..16])
}

/// What a device signs to log in: the context string followed by the relay's nonce.
pub fn auth_message(nonce: &[u8]) -> Vec<u8> {
    [AUTH_CONTEXT, nonce].concat()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wire_format_is_stable() {
        let m = ClientMsg::Send { to: "ab".into(), sid: "s1".into(), data: "AAA=".into() };
        assert_eq!(serde_json::to_string(&m).unwrap(), r#"{"t":"send","to":"ab","sid":"s1","data":"AAA="}"#);
        let s: ServerMsg = serde_json::from_str(r#"{"t":"ready","id":"x"}"#).unwrap();
        assert_eq!(s, ServerMsg::Ready { id: "x".into() });
    }

    /// Same cases as RelayProtocolTest.relayUrlRules in android/core: keep them in sync.
    #[test]
    fn relay_url_rules() {
        for ok in [
            "ws://192.168.0.102:8080/v1/ws",
            "ws://10.1.2.3/v1/ws",
            "ws://172.16.0.1:8080/v1/ws",
            "ws://127.0.0.1:8080/v1/ws",
            "ws://localhost:8080/v1/ws",
            "ws://desktop-6fqctja.local:8080/v1/ws",
            "ws://[fd00::1]:8080/v1/ws",
            "ws://[fe80::1]:8080/v1/ws",
            "wss://relay.example.com/v1/ws",
            "WSS://8.8.8.8/v1/ws",
        ] {
            assert!(check_relay_url(ok).is_ok(), "{ok}");
        }
        for bad in [
            "ws://relay.example.com/v1/ws",
            "ws://8.8.8.8:8080/v1/ws",
            "ws://172.32.0.1/v1/ws",
            "ws://[2001:db8::1]/v1/ws",
            "ws://192.168.0.1.evil.com/v1/ws",
            "http://192.168.0.102:8080",
            "ws:///v1/ws",
        ] {
            assert!(check_relay_url(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn device_id_is_32_hex_chars() {
        let id = device_id(&[0u8; 32]);
        assert_eq!(id.len(), 32);
        assert_eq!(id, "66687aadf862bd776c8fc18b8e9f8e20");
    }
}
