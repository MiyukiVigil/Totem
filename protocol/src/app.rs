//! Messages inside the Noise channel (JSON), and the pairing QR code.
//!
//! Pairing (phone = Noise initiator, PC = responder, see [`crate::noise`]):
//! 1. PC shows [`PairingQr`] as `totem://pair?...`.
//! 2. Phone → PC, handshake message 1 payload: [`PairingHello`] (includes the QR secret).
//! 3. PC → phone, handshake message 2 payload: [`PairingWelcome`].
//! 4. Both show the confirmation code; phone → PC transport message: [`PairingDone`].
//!
//! Requests (PC = initiator, phone = responder): after the two handshake messages, the PC sends
//! one [`PcToPhone`] per request as a transport message and the phone answers with [`PhoneToPc`].

use serde::{Deserialize, Serialize};

pub const PAIRING_URI_PREFIX: &str = "totem://pair?";
pub const PAIRING_QR_VERSION: u32 = 1;
/// Requests whose `ts` is further than this from the phone's clock are refused.
pub const MAX_CLOCK_SKEW_SECS: u64 = 120;

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct PairingHello {
    /// Base64 of the 32-byte secret from the QR code.
    pub secret: String,
    /// The phone's relay device id, so the PC can address it later.
    pub phone_id: String,
    /// Shown on the PC, e.g. "Nothing Phone (2a)".
    pub name: String,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct PairingWelcome {
    /// Shown on the phone, e.g. the PC's hostname.
    pub name: String,
}

/// Phone → PC, first transport message after a pairing handshake: did the user confirm that
/// both screens show the same code? The PC only saves the pairing on `accepted: true`.
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct PairingDone {
    pub accepted: bool,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum PcToPhone {
    ListAccounts { req: String, ts: u64 },
    GetCode { req: String, ts: u64, account: String },
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum PhoneToPc {
    Accounts { req: String, items: Vec<AccountInfo> },
    Code {
        req: String,
        code: String,
        /// Unix seconds when `code` stops being valid.
        valid_until: u64,
        /// Sent when `code` has under 5 seconds left.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        next_code: Option<String>,
    },
    Denied { req: String, reason: String },
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq, Eq)]
pub struct AccountInfo {
    pub id: String,
    pub issuer: String,
    pub label: String,
    /// "auto" | "ask" | "never"
    pub policy: String,
    /// Brand icon slug (Simple Icons) chosen or matched on the phone; absent = no logo.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
}

/// Contents of the pairing QR code.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingQr {
    pub relay: String,
    pub pc_id: String,
    /// PC's Noise static public key.
    pub pc_key: Vec<u8>,
    pub secret: Vec<u8>,
    /// Unix seconds after which the phone refuses the code.
    pub expires: u64,
}

impl PairingQr {
    pub fn to_uri(&self) -> String {
        use base64::Engine;
        let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
        format!(
            "{PAIRING_URI_PREFIX}v={PAIRING_QR_VERSION}&relay={}&id={}&k={}&s={}&exp={}",
            urlencoding::encode(&self.relay),
            self.pc_id,
            b64.encode(&self.pc_key),
            b64.encode(&self.secret),
            self.expires
        )
    }

    pub fn parse(uri: &str) -> Result<Self, String> {
        use base64::Engine;
        let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD;
        let query = uri.strip_prefix(PAIRING_URI_PREFIX).ok_or("not a Totem pairing code")?;
        let get = |k: &str| {
            query
                .split('&')
                .find_map(|p| p.strip_prefix(k).and_then(|r| r.strip_prefix('=')))
                .ok_or(format!("pairing code has no {k}"))
        };
        if get("v")? != PAIRING_QR_VERSION.to_string() {
            return Err("pairing code is from a different Totem version".into());
        }
        Ok(Self {
            relay: urlencoding::decode(get("relay")?).map_err(|e| e.to_string())?.into_owned(),
            pc_id: get("id")?.to_string(),
            pc_key: b64.decode(get("k")?).map_err(|e| e.to_string())?,
            secret: b64.decode(get("s")?).map_err(|e| e.to_string())?,
            expires: get("exp")?.parse().map_err(|_| "bad expiry")?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pairing_qr_round_trips() {
        let qr = PairingQr {
            relay: "ws://192.168.0.102:8080/v1/ws".into(),
            pc_id: "0123456789abcdef0123456789abcdef".into(),
            pc_key: vec![1; 32],
            secret: vec![2; 32],
            expires: 1_800_000_000,
        };
        let uri = qr.to_uri();
        // Same literal as PairingQrTest in android/core: keep them in sync.
        assert_eq!(
            uri,
            "totem://pair?v=1&relay=ws%3A%2F%2F192.168.0.102%3A8080%2Fv1%2Fws&id=0123456789abcdef0123456789abcdef\
             &k=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE&s=AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI&exp=1800000000"
        );
        assert_eq!(PairingQr::parse(&uri).unwrap(), qr);
        assert!(PairingQr::parse("totem://pair?v=2&relay=x").is_err());
        assert!(PairingQr::parse("otpauth://totp/x").is_err());
    }

    #[test]
    fn app_messages_wire_format() {
        let m = PhoneToPc::Code { req: "r".into(), code: "123456".into(), valid_until: 30, next_code: None };
        assert_eq!(serde_json::to_string(&m).unwrap(), r#"{"type":"code","req":"r","code":"123456","valid_until":30}"#);
        let p: PcToPhone = serde_json::from_str(r#"{"type":"get_code","req":"r","ts":5,"account":"a"}"#).unwrap();
        assert_eq!(p, PcToPhone::GetCode { req: "r".into(), ts: 5, account: "a".into() });
    }
}
