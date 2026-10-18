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

    #[test]
    fn device_id_is_32_hex_chars() {
        let id = device_id(&[0u8; 32]);
        assert_eq!(id.len(), 32);
        assert_eq!(id, "66687aadf862bd776c8fc18b8e9f8e20");
    }
}
