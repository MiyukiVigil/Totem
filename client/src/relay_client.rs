//! A logged-in relay connection.

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use ed25519_dalek::{Signer, SigningKey};
use futures_util::{SinkExt, StreamExt};
use std::time::Duration;
use tokio_tungstenite::tungstenite::Message;
use totem_protocol::relay::{auth_message, ClientMsg, ServerMsg};

type Ws = tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

pub struct RelayClient {
    ws: Ws,
}

/// A message from another device.
pub struct Incoming {
    pub from: String,
    pub sid: String,
    pub data: Vec<u8>,
}

impl RelayClient {
    pub async fn connect(url: &str, key: &SigningKey) -> Result<Self, String> {
        let (ws, _) = tokio::time::timeout(Duration::from_secs(10), tokio_tungstenite::connect_async(url))
            .await
            .map_err(|_| format!("timed out connecting to relay {url}"))?
            .map_err(|e| format!("can't reach relay {url}: {e}"))?;
        let mut client = Self { ws };
        let ServerMsg::Challenge { nonce } = client.next(Duration::from_secs(10)).await? else {
            return Err("relay did not send a challenge".into());
        };
        let nonce = B64.decode(nonce).map_err(|e| e.to_string())?;
        let sig = key.sign(&auth_message(&nonce));
        client
            .send(&ClientMsg::Auth { pubkey: B64.encode(key.verifying_key().as_bytes()), sig: B64.encode(sig.to_bytes()) })
            .await?;
        match client.next(Duration::from_secs(10)).await? {
            ServerMsg::Ready { .. } => Ok(client),
            ServerMsg::Error { msg, .. } => Err(format!("relay refused login: {msg}")),
            other => Err(format!("unexpected relay reply: {other:?}")),
        }
    }

    async fn send(&mut self, m: &ClientMsg) -> Result<(), String> {
        self.ws.send(Message::Text(serde_json::to_string(m).unwrap())).await.map_err(|e| e.to_string())
    }

    async fn next(&mut self, timeout: Duration) -> Result<ServerMsg, String> {
        loop {
            let msg = tokio::time::timeout(timeout, self.ws.next())
                .await
                .map_err(|_| "timed out".to_string())?
                .ok_or("relay closed the connection")?
                .map_err(|e| e.to_string())?;
            if let Message::Text(text) = msg {
                return serde_json::from_str(&text).map_err(|e| format!("unreadable relay message: {e}"));
            }
        }
    }

    pub async fn send_to(&mut self, to: &str, sid: &str, data: &[u8]) -> Result<(), String> {
        self.send(&ClientMsg::Send { to: to.into(), sid: sid.into(), data: B64.encode(data) }).await
    }

    /// Next message from a device. Relay errors are returned as `Err`.
    pub async fn recv(&mut self, timeout: Duration) -> Result<Incoming, String> {
        match self.next(timeout).await? {
            ServerMsg::Msg { from, sid, data } => Ok(Incoming { from, sid, data: B64.decode(data).map_err(|e| e.to_string())? }),
            ServerMsg::Error { msg, .. } => Err(format!("relay: {msg}")),
            other => Err(format!("unexpected relay message: {other:?}")),
        }
    }

    /// Next message for session `sid`, ignoring anything else.
    pub async fn recv_session(&mut self, sid: &str, timeout: Duration) -> Result<Incoming, String> {
        let deadline = tokio::time::Instant::now() + timeout;
        loop {
            let left = deadline.saturating_duration_since(tokio::time::Instant::now());
            if left.is_zero() {
                return Err("timed out".into());
            }
            let m = self.recv(left).await?;
            if m.sid == sid {
                return Ok(m);
            }
        }
    }
}
