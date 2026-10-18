//! Pairing and code requests against a real relay, with a fake phone written in Rust that
//! follows the same protocol as the Android app.

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use ed25519_dalek::SigningKey;
use std::time::Duration;
use totem_client::config::Config;
use totem_client::relay_client::RelayClient;
use totem_client::{pair, AccountInfo, PairEvent, Session};
use totem_protocol::app::{PairingDone, PairingHello, PairingQr, PcToPhone, PhoneToPc};
use totem_protocol::noise::{self, MAX_MESSAGE_LEN};
use totem_protocol::relay::device_id;

/// `TOTEM_TEST_RELAY_URL` runs against another relay (see relay/tests/relay.rs).
async fn start_relay() -> String {
    if let Ok(url) = std::env::var("TOTEM_TEST_RELAY_URL") {
        return url;
    }
    let relay = totem_relay::Relay::new(None, None);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, relay.router()).await.unwrap() });
    format!("ws://{addr}/v1/ws")
}

/// Plays the phone: pairs using `uri`, then answers sessions like the Android RequestHandler.
async fn fake_phone(uri: String, accept: bool) {
    let qr = PairingQr::parse(&uri).unwrap();
    let key = SigningKey::generate(&mut rand::rngs::OsRng);
    let me = device_id(key.verifying_key().as_bytes());
    let noise_kp = noise::generate_keypair().unwrap();
    let mut relay = RelayClient::connect(&qr.relay, &key).await.unwrap();

    let mut buf = vec![0u8; MAX_MESSAGE_LEN];
    let mut payload = vec![0u8; MAX_MESSAGE_LEN];
    let mut hs = noise::pairing_initiator(&noise_kp.private, &qr.pc_key).unwrap();
    let hello = PairingHello { secret: B64.encode(&qr.secret), phone_id: me.clone(), name: "Fake Phone".into() };
    let n = hs.write_message(&serde_json::to_vec(&hello).unwrap(), &mut buf).unwrap();
    relay.send_to(&qr.pc_id, "pair", &buf[..n]).await.unwrap();
    let reply = relay.recv_session("pair", Duration::from_secs(5)).await.unwrap();
    hs.read_message(&reply.data, &mut payload).unwrap();
    let mut t = hs.into_transport_mode().unwrap();
    let n = t.write_message(&serde_json::to_vec(&PairingDone { accepted: accept }).unwrap(), &mut buf).unwrap();
    relay.send_to(&qr.pc_id, "pair", &buf[..n]).await.unwrap();
    if !accept {
        return;
    }

    // Serve sessions.
    let mut sessions = std::collections::HashMap::new();
    while let Ok(m) = relay.recv(Duration::from_secs(10)).await {
        let Some(t) = sessions.get_mut(&m.sid) else {
            let mut hs = noise::session_responder(&noise_kp.private).unwrap();
            hs.read_message(&m.data, &mut payload).unwrap();
            assert_eq!(hs.get_remote_static().unwrap(), qr.pc_key.as_slice(), "only the paired PC");
            let n = hs.write_message(&[], &mut buf).unwrap();
            relay.send_to(&m.from, &m.sid, &buf[..n]).await.unwrap();
            sessions.insert(m.sid.clone(), hs.into_transport_mode().unwrap());
            continue;
        };
        let n = t.read_message(&m.data, &mut payload).unwrap();
        let reply = match serde_json::from_slice::<PcToPhone>(&payload[..n]).unwrap() {
            PcToPhone::ListAccounts { req, .. } => PhoneToPc::Accounts {
                req,
                items: vec![
                    AccountInfo { id: "gh".into(), issuer: "GitHub".into(), label: "me".into(), policy: "auto".into() },
                    AccountInfo { id: "bank".into(), issuer: "Bank".into(), label: "".into(), policy: "never".into() },
                ],
            },
            PcToPhone::GetCode { req, account, .. } if account == "gh" => {
                PhoneToPc::Code { req, code: "123456".into(), valid_until: totem_client::now() + 20, next_code: None }
            }
            PcToPhone::GetCode { req, .. } => PhoneToPc::Denied { req, reason: "policy_never".into() },
        };
        let n = t.write_message(&serde_json::to_vec(&reply).unwrap(), &mut buf).unwrap();
        relay.send_to(&m.from, &m.sid, &buf[..n]).await.unwrap();
    }
}

#[tokio::test]
async fn pair_then_list_and_get_codes() {
    // Keep keys and settings out of the real Credential Manager / %APPDATA%.
    let dir = std::env::temp_dir().join(format!("totem-e2e-{}", rand::random::<u64>()));
    std::env::set_var("TOTEM_CONFIG_DIR", &dir);
    let relay = start_relay().await;
    let mut config = Config::load_or_create().unwrap();

    // Rejected pairing first: nothing gets saved.
    let mut phone_task = None;
    let result = pair(&mut config, &relay, |e| {
        if let PairEvent::ShowQr(uri) = e {
            phone_task = Some(tokio::spawn(fake_phone(uri, false)));
        }
    })
    .await;
    assert_eq!(result.unwrap_err(), "pairing was cancelled on the phone");
    assert!(config.phones().is_empty());

    // Accepted pairing.
    let mut confirm_code = None;
    let phone = pair(&mut config, &relay, |e| match e {
        PairEvent::ShowQr(uri) => {
            tokio::spawn(fake_phone(uri, true));
        }
        PairEvent::Confirm { code, .. } => confirm_code = Some(code),
    })
    .await
    .unwrap();
    assert_eq!(phone.name, "Fake Phone");
    assert_eq!(confirm_code.unwrap().len(), 6);

    // Settings survive a reload, and requests work.
    let config = Config::load_or_create().unwrap();
    assert_eq!(config.phones().len(), 1);
    let mut session = Session::open(&config).await.unwrap();
    let accounts = session.accounts().await.unwrap();
    assert_eq!(accounts.len(), 2);
    assert_eq!(session.code("gh").await.unwrap().code, "123456");
    assert_eq!(session.code("bank").await.unwrap_err(), "this account is set to phone-only");

    config.reset().unwrap();
    let _ = std::fs::remove_dir_all(dir);
}
