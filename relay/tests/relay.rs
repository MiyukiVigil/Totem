//! Starts a real relay on a random port and talks to it over WebSockets.

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use ed25519_dalek::{Signer, SigningKey};
use futures_util::{SinkExt, StreamExt};
use std::time::Duration;
use tokio_tungstenite::tungstenite::Message;
use totem_protocol::relay::{auth_message, device_id, ClientMsg, ServerMsg};

type Ws = tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

async fn start_relay() -> String {
    let relay = totem_relay::Relay::new(None, None);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, relay.router()).await.unwrap() });
    format!("ws://{addr}/v1/ws")
}

async fn recv(ws: &mut Ws) -> ServerMsg {
    let msg = tokio::time::timeout(Duration::from_secs(5), ws.next()).await.expect("timeout").unwrap().unwrap();
    serde_json::from_str(msg.to_text().unwrap()).unwrap()
}

async fn send(ws: &mut Ws, m: &ClientMsg) {
    ws.send(Message::Text(serde_json::to_string(m).unwrap())).await.unwrap();
}

/// Connects and logs in; returns the socket and the device id.
async fn login(url: &str, key: &SigningKey) -> (Ws, String) {
    let (mut ws, _) = tokio_tungstenite::connect_async(url).await.unwrap();
    let ServerMsg::Challenge { nonce } = recv(&mut ws).await else { panic!("expected challenge") };
    let sig = key.sign(&auth_message(&B64.decode(nonce).unwrap()));
    send(&mut ws, &ClientMsg::Auth { pubkey: B64.encode(key.verifying_key().as_bytes()), sig: B64.encode(sig.to_bytes()) }).await;
    let ServerMsg::Ready { id } = recv(&mut ws).await else { panic!("expected ready") };
    assert_eq!(id, device_id(key.verifying_key().as_bytes()));
    (ws, id)
}

fn key() -> SigningKey {
    SigningKey::generate(&mut rand::rngs::OsRng)
}

#[tokio::test]
async fn forwards_between_online_devices() {
    let url = start_relay().await;
    let (mut pc, pc_id) = login(&url, &key()).await;
    let (mut phone, phone_id) = login(&url, &key()).await;
    send(&mut pc, &ClientMsg::Send { to: phone_id.clone(), sid: "s1".into(), data: "aGk=".into() }).await;
    assert_eq!(recv(&mut phone).await, ServerMsg::Msg { from: pc_id.clone(), sid: "s1".into(), data: "aGk=".into() });
    send(&mut phone, &ClientMsg::Send { to: pc_id, sid: "s1".into(), data: "eW8=".into() }).await;
    assert!(matches!(recv(&mut pc).await, ServerMsg::Msg { data, .. } if data == "eW8="));
}

#[tokio::test]
async fn rejects_bad_signature() {
    let url = start_relay().await;
    let (mut ws, _) = tokio_tungstenite::connect_async(&url).await.unwrap();
    let ServerMsg::Challenge { .. } = recv(&mut ws).await else { panic!() };
    let k = key();
    let sig = k.sign(b"some other message");
    send(&mut ws, &ClientMsg::Auth { pubkey: B64.encode(k.verifying_key().as_bytes()), sig: B64.encode(sig.to_bytes()) }).await;
    assert!(matches!(recv(&mut ws).await, ServerMsg::Error { code, .. } if code == "auth_failed"));
}

#[tokio::test]
async fn holds_messages_for_offline_device() {
    let url = start_relay().await;
    let phone_key = key();
    let phone_id = device_id(phone_key.verifying_key().as_bytes());
    let (mut pc, pc_id) = login(&url, &key()).await;
    send(&mut pc, &ClientMsg::Send { to: phone_id, sid: "s".into(), data: "AA==".into() }).await;
    tokio::time::sleep(Duration::from_millis(200)).await;
    // Phone connects later (as if woken by a push) and gets the held message after "ready".
    let (mut phone, _) = login(&url, &phone_key).await;
    assert_eq!(recv(&mut phone).await, ServerMsg::Msg { from: pc_id, sid: "s".into(), data: "AA==".into() });
}

#[tokio::test]
async fn allow_list_blocks_strangers() {
    let url = start_relay().await;
    let (mut phone, phone_id) = login(&url, &key()).await;
    let (mut pc, pc_id) = login(&url, &key()).await;
    let (mut stranger, _) = login(&url, &key()).await;
    send(&mut phone, &ClientMsg::Allow { peers: vec![pc_id] }).await;
    tokio::time::sleep(Duration::from_millis(100)).await;

    send(&mut stranger, &ClientMsg::Send { to: phone_id.clone(), sid: "x".into(), data: "AA==".into() }).await;
    assert!(matches!(recv(&mut stranger).await, ServerMsg::Error { code, .. } if code == "not_allowed"));
    send(&mut pc, &ClientMsg::Send { to: phone_id, sid: "y".into(), data: "AQ==".into() }).await;
    assert!(matches!(recv(&mut phone).await, ServerMsg::Msg { sid, .. } if sid == "y"));
}

#[tokio::test]
async fn rejects_oversized_messages() {
    let url = start_relay().await;
    let (mut a, _) = login(&url, &key()).await;
    let (_b, b_id) = login(&url, &key()).await;
    send(&mut a, &ClientMsg::Send { to: b_id, sid: "s".into(), data: "A".repeat(17 * 1024) }).await;
    // Either the relay answers too_large, or the socket is closed for exceeding the frame limit.
    match tokio::time::timeout(Duration::from_secs(5), a.next()).await.unwrap() {
        Some(Ok(Message::Text(t))) => assert!(t.contains("too_large"), "{t}"),
        _ => {}
    }
}
