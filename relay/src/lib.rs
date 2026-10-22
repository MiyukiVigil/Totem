//! Totem relay. See `totem_protocol::relay` for the wire format.
//!
//! What it stores (in a small JSON file): each phone's FCM token and its `allow` list.
//! What it holds briefly in memory: messages for an offline device, for up to [`HOLD_FOR`],
//! while a push wakes it. It never sees anything but device ids and Noise ciphertext.

pub mod fcm;

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::State;
use axum::response::IntoResponse;
use axum::routing::get;
use axum::Router;
use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use futures_util::{SinkExt, StreamExt};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, Mutex};
use totem_protocol::relay::{auth_message, device_id, ClientMsg, ServerMsg, MAX_DATA_LEN};

/// How long a message waits for an offline device. Covers a cold phone start, and outlasts the
/// PC's 25 s wait (client `PHONE_TIMEOUT`).
pub const HOLD_FOR: Duration = Duration::from_secs(30);
const AUTH_TIMEOUT: Duration = Duration::from_secs(10);
const MIN_WAKE_INTERVAL: Duration = Duration::from_secs(2);
/// Per-connection limit: this many messages per [`RATE_WINDOW`].
const RATE_LIMIT: u32 = 60;
const RATE_WINDOW: Duration = Duration::from_secs(10);

#[derive(Serialize, Deserialize, Default)]
struct Store {
    fcm_tokens: HashMap<String, String>,
    allow: HashMap<String, HashSet<String>>,
}

struct Conn {
    serial: u64,
    tx: mpsc::UnboundedSender<String>,
}

pub struct Relay {
    conns: Mutex<HashMap<String, Conn>>,
    pending: Mutex<HashMap<String, Vec<(Instant, String)>>>,
    store: Mutex<Store>,
    store_path: Option<PathBuf>,
    fcm: Option<fcm::Fcm>,
    last_wake: Mutex<HashMap<String, Instant>>,
    serial: AtomicU64,
}

impl Relay {
    /// `store_path = None` keeps everything in memory (tests).
    pub fn new(store_path: Option<PathBuf>, fcm: Option<fcm::Fcm>) -> Arc<Self> {
        let store = store_path
            .as_ref()
            .and_then(|p| std::fs::read_to_string(p).ok())
            .and_then(|t| serde_json::from_str(&t).ok())
            .unwrap_or_default();
        Arc::new(Self {
            conns: Mutex::default(),
            pending: Mutex::default(),
            store: Mutex::new(store),
            store_path,
            fcm,
            last_wake: Mutex::default(),
            serial: AtomicU64::new(0),
        })
    }

    pub fn router(self: &Arc<Self>) -> Router {
        Router::new()
            .route("/v1/ws", get(ws_handler))
            .route("/health", get(|| async { "ok" }))
            .with_state(self.clone())
    }

    async fn save_store(&self, store: &Store) {
        if let Some(path) = &self.store_path {
            let json = serde_json::to_string_pretty(store).unwrap();
            let tmp = path.with_extension("tmp");
            if tokio::fs::write(&tmp, json).await.is_ok() {
                let _ = tokio::fs::rename(&tmp, path).await;
            }
        }
    }

    async fn deliver(self: &Arc<Self>, from: &str, to: &str, sid: String, data: String) -> Result<(), ServerMsg> {
        if data.len() > MAX_DATA_LEN {
            return Err(error("too_large", "message too large"));
        }
        if let Some(allowed) = self.store.lock().await.allow.get(to) {
            if !allowed.contains(from) {
                return Err(error("not_allowed", "that device has not paired with you"));
            }
        }
        let frame = serde_json::to_string(&ServerMsg::Msg { from: from.into(), sid, data }).unwrap();
        if let Some(conn) = self.conns.lock().await.get(to) {
            if conn.tx.send(frame.clone()).is_ok() {
                return Ok(());
            }
        }
        // Offline: hold it and wake the device.
        {
            let mut pending = self.pending.lock().await;
            let queue = pending.entry(to.to_string()).or_default();
            queue.retain(|(at, _)| at.elapsed() < HOLD_FOR);
            if queue.len() >= 32 {
                return Err(error("queue_full", "too many messages waiting for that device"));
            }
            queue.push((Instant::now(), frame));
        }
        self.wake(to).await;
        Ok(())
    }

    async fn wake(self: &Arc<Self>, id: &str) {
        let Some(token) = self.store.lock().await.fcm_tokens.get(id).cloned() else {
            tracing::info!(device = id, "offline and no push token; message held");
            return;
        };
        {
            let mut last = self.last_wake.lock().await;
            if last.get(id).is_some_and(|t| t.elapsed() < MIN_WAKE_INTERVAL) {
                return;
            }
            last.insert(id.to_string(), Instant::now());
        }
        let relay = self.clone();
        let id = id.to_string();
        tokio::spawn(async move {
            if let Some(fcm) = &relay.fcm {
                match fcm.wake(&token).await {
                    Ok(()) => tracing::info!(device = id, "wake push sent"),
                    Err(e) => tracing::warn!(device = id, "wake push failed: {e}"),
                }
            }
        });
    }
}

fn error(code: &str, msg: &str) -> ServerMsg {
    ServerMsg::Error { code: code.into(), msg: msg.into() }
}

async fn ws_handler(ws: WebSocketUpgrade, State(relay): State<Arc<Relay>>) -> impl IntoResponse {
    ws.max_message_size(MAX_DATA_LEN + 1024).on_upgrade(move |socket| handle(socket, relay))
}

async fn handle(socket: WebSocket, relay: Arc<Relay>) {
    let (mut sink, mut stream) = socket.split();
    let send_now = |m: &ServerMsg| Message::Text(serde_json::to_string(m).unwrap());

    // 1. Challenge / response login.
    let mut nonce = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut nonce);
    if sink.send(send_now(&ServerMsg::Challenge { nonce: B64.encode(nonce) })).await.is_err() {
        return;
    }
    let id = match tokio::time::timeout(AUTH_TIMEOUT, stream.next()).await {
        Ok(Some(Ok(Message::Text(text)))) => match verify_auth(&text, &nonce) {
            Ok(id) => id,
            Err(e) => {
                let _ = sink.send(send_now(&error("auth_failed", &e))).await;
                return;
            }
        },
        _ => return,
    };

    // 2. Register the connection (a newer connection for the same device replaces the old one).
    let serial = relay.serial.fetch_add(1, Ordering::Relaxed);
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
    relay.conns.lock().await.insert(id.clone(), Conn { serial, tx: tx.clone() });
    let _ = tx.send(serde_json::to_string(&ServerMsg::Ready { id: id.clone() }).unwrap());
    if let Some(queue) = relay.pending.lock().await.remove(&id) {
        for (at, frame) in queue {
            if at.elapsed() < HOLD_FOR {
                let _ = tx.send(frame);
            }
        }
    }
    tracing::info!(device = id, "connected");

    let writer = tokio::spawn(async move {
        while let Some(frame) = rx.recv().await {
            if sink.send(Message::Text(frame)).await.is_err() {
                break;
            }
        }
    });

    // 3. Handle requests.
    let mut window_start = Instant::now();
    let mut count = 0u32;
    while let Some(Ok(msg)) = stream.next().await {
        let Message::Text(text) = msg else { continue };
        if window_start.elapsed() > RATE_WINDOW {
            window_start = Instant::now();
            count = 0;
        }
        count += 1;
        if count > RATE_LIMIT {
            let _ = tx.send(serde_json::to_string(&error("rate_limited", "slow down")).unwrap());
            continue;
        }
        let reply = match serde_json::from_str::<ClientMsg>(&text) {
            Err(_) => Some(error("bad_request", "unreadable message")),
            Ok(ClientMsg::Auth { .. }) => Some(error("bad_request", "already logged in")),
            Ok(ClientMsg::RegisterPush { fcm }) => {
                let mut store = relay.store.lock().await;
                store.fcm_tokens.insert(id.clone(), fcm);
                relay.save_store(&store).await;
                None
            }
            Ok(ClientMsg::Allow { peers }) => {
                let mut store = relay.store.lock().await;
                store.allow.insert(id.clone(), peers.into_iter().take(64).collect());
                relay.save_store(&store).await;
                None
            }
            Ok(ClientMsg::Send { to, sid, data }) => relay.deliver(&id, &to, sid, data).await.err(),
        };
        if let Some(reply) = reply {
            let _ = tx.send(serde_json::to_string(&reply).unwrap());
        }
    }

    // 4. Clean up, unless a newer connection already replaced this one.
    {
        let mut conns = relay.conns.lock().await;
        if conns.get(&id).is_some_and(|c| c.serial == serial) {
            conns.remove(&id);
        }
    }
    drop(tx);
    let _ = writer.await;
    tracing::info!(device = id, "disconnected");
}

fn verify_auth(text: &str, nonce: &[u8]) -> Result<String, String> {
    let Ok(ClientMsg::Auth { pubkey, sig }) = serde_json::from_str::<ClientMsg>(text) else {
        return Err("expected auth".into());
    };
    let pubkey = B64.decode(pubkey).map_err(|_| "bad public key")?;
    let key_bytes: [u8; 32] = pubkey.as_slice().try_into().map_err(|_| "bad public key")?;
    let key = VerifyingKey::from_bytes(&key_bytes).map_err(|_| "bad public key")?;
    let sig = Signature::from_slice(&B64.decode(sig).map_err(|_| "bad signature")?).map_err(|_| "bad signature")?;
    key.verify(&auth_message(nonce), &sig).map_err(|_| "signature does not match")?;
    Ok(device_id(&pubkey))
}
