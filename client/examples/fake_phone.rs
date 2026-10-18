//! A stand-in phone for testing the PC side without a real one:
//!
//! ```text
//! cargo run -p totem-client --example fake_phone -- '<totem://pair?... link>'
//! ```
//! It pairs (accepting automatically), then answers requests until killed, with three demo
//! accounts: "Demo GitHub" (auto, RFC 6238 test secret), "Demo Bank" (ask: approves after 1 s)
//! and "Demo Vault" (phone only).

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use ed25519_dalek::SigningKey;
use std::collections::HashMap;
use std::time::Duration;
use totem_client::relay_client::RelayClient;
use totem_client::AccountInfo;
use totem_protocol::app::{PairingDone, PairingHello, PairingQr, PcToPhone, PhoneToPc};
use totem_protocol::noise::{self, MAX_MESSAGE_LEN};
use totem_protocol::otp;
use totem_protocol::relay::device_id;

const RFC_SECRET: &[u8] = b"12345678901234567890";

#[tokio::main]
async fn main() {
    let uri = std::env::args().nth(1).expect("usage: fake_phone <totem://pair?... link>");
    let qr = PairingQr::parse(&uri).expect("not a pairing link");
    let key = SigningKey::generate(&mut rand::rngs::OsRng);
    let me = device_id(key.verifying_key().as_bytes());
    let kp = noise::generate_keypair().unwrap();
    let mut relay = RelayClient::connect(&qr.relay, &key).await.expect("relay");

    let mut buf = vec![0u8; MAX_MESSAGE_LEN];
    let mut payload = vec![0u8; MAX_MESSAGE_LEN];
    let mut hs = noise::pairing_initiator(&kp.private, &qr.pc_key).unwrap();
    let hello = PairingHello { secret: B64.encode(&qr.secret), phone_id: me, name: "Fake Phone".into() };
    let n = hs.write_message(&serde_json::to_vec(&hello).unwrap(), &mut buf).unwrap();
    relay.send_to(&qr.pc_id, "pair", &buf[..n]).await.unwrap();
    let reply = relay.recv_session("pair", Duration::from_secs(10)).await.expect("PC answered");
    hs.read_message(&reply.data, &mut payload).expect("PC verified");
    println!("pairing code: {}", noise::confirmation_code(hs.get_handshake_hash()));
    let mut t = hs.into_transport_mode().unwrap();
    let n = t.write_message(&serde_json::to_vec(&PairingDone { accepted: true }).unwrap(), &mut buf).unwrap();
    relay.send_to(&qr.pc_id, "pair", &buf[..n]).await.unwrap();
    println!("paired; serving requests");

    let accounts = vec![
        AccountInfo { id: "gh".into(), issuer: "Demo GitHub".into(), label: "octocat".into(), policy: "auto".into(), icon: Some("github".into()) },
        AccountInfo { id: "bank".into(), issuer: "Demo Bank".into(), label: "".into(), policy: "ask".into(), icon: None },
        AccountInfo { id: "vault".into(), issuer: "Demo Vault".into(), label: "".into(), policy: "never".into(), icon: None },
    ];
    let mut sessions = HashMap::new();
    loop {
        let m = match relay.recv(Duration::from_secs(3600)).await {
            Ok(m) => m,
            Err(e) => {
                eprintln!("relay: {e}");
                return;
            }
        };
        let Some(t) = sessions.get_mut(&m.sid) else {
            let mut hs = noise::session_responder(&kp.private).unwrap();
            if hs.read_message(&m.data, &mut payload).is_err() || hs.get_remote_static() != Some(qr.pc_key.as_slice()) {
                continue;
            }
            let n = hs.write_message(&[], &mut buf).unwrap();
            relay.send_to(&m.from, &m.sid, &buf[..n]).await.unwrap();
            sessions.insert(m.sid.clone(), hs.into_transport_mode().unwrap());
            continue;
        };
        let Ok(n) = t.read_message(&m.data, &mut payload) else { continue };
        let now = totem_client::now();
        let reply = match serde_json::from_slice::<PcToPhone>(&payload[..n]).unwrap() {
            PcToPhone::ListAccounts { req, .. } => PhoneToPc::Accounts { req, items: accounts.clone() },
            PcToPhone::GetCode { req, account, .. } => match account.as_str() {
                "vault" => PhoneToPc::Denied { req, reason: "policy_never".into() },
                id => {
                    if id == "bank" {
                        println!("(pretending the user taps Approve…)");
                        tokio::time::sleep(Duration::from_secs(1)).await;
                    }
                    let code = otp::totp(RFC_SECRET, now, 30, 6, otp::Algorithm::Sha1).unwrap();
                    println!("sent code for {id}");
                    PhoneToPc::Code { req, code, valid_until: now + otp::seconds_remaining(now, 30), next_code: None }
                }
            },
        };
        let n = t.write_message(&serde_json::to_vec(&reply).unwrap(), &mut buf).unwrap();
        relay.send_to(&m.from, &m.sid, &buf[..n]).await.unwrap();
    }
}
