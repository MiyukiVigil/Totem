//! The PC side of Totem, shared by the `totem` CLI and the desktop app:
//! keys and settings ([`config`]), pairing ([`pair`]) and code requests ([`Session`]).

pub mod config;
pub mod relay_client;

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use config::{Config, Phone};
use rand::RngCore;
use relay_client::RelayClient;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
pub use totem_protocol::app::AccountInfo;
use totem_protocol::app::{PairingDone, PairingHello, PairingQr, PairingWelcome, PcToPhone, PhoneToPc};
use totem_protocol::noise::{self, MAX_MESSAGE_LEN};

/// How long to wait for the phone: it may need a push to wake up.
pub const PHONE_TIMEOUT: Duration = Duration::from_secs(25);
/// "Ask first" accounts wait for a tap on the phone.
pub const APPROVAL_TIMEOUT: Duration = Duration::from_secs(45);
pub const PAIRING_VALID_FOR: u64 = 180;

pub fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs()
}

fn random_id() -> String {
    let mut b = [0u8; 12];
    rand::thread_rng().fill_bytes(&mut b);
    hex::encode(b)
}

pub fn pc_name() -> String {
    std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME")).unwrap_or_else(|_| "PC".into())
}

/// "Issuer (label)", or whichever of the two exists.
pub fn describe(a: &AccountInfo) -> String {
    match (a.issuer.is_empty(), a.label.is_empty()) {
        (false, false) => format!("{} ({})", a.issuer, a.label),
        (false, true) => a.issuer.clone(),
        _ => a.label.clone(),
    }
}

/// Progress reports from [`pair`].
#[derive(Debug, Clone)]
pub enum PairEvent {
    /// Show this `totem://pair?...` link as a QR code.
    ShowQr(String),
    /// A phone answered: show the code and wait for the user to confirm on the phone.
    Confirm { phone_name: String, code: String },
}

/// Pairs with a phone through `relay_url`. Calls `on_event` as things happen; returns the
/// phone once the user confirmed on it. The new phone replaces any earlier one.
pub async fn pair(config: &mut Config, relay_url: &str, mut on_event: impl FnMut(PairEvent)) -> Result<Phone, String> {
    config.set_relay_url(relay_url.to_string());
    config.save()?;

    let mut secret = vec![0u8; noise::PAIRING_SECRET_LEN];
    rand::thread_rng().fill_bytes(&mut secret);
    let qr = PairingQr {
        relay: relay_url.to_string(),
        pc_id: config.device_id(),
        pc_key: config.noise_public(),
        secret: secret.clone(),
        expires: now() + PAIRING_VALID_FOR,
    };
    let mut relay = RelayClient::connect(relay_url, &config.signing_key()).await?;
    on_event(PairEvent::ShowQr(qr.to_uri()));

    // Wait for a phone's handshake carrying the right secret. Anything else is ignored.
    let deadline = tokio::time::Instant::now() + Duration::from_secs(PAIRING_VALID_FOR);
    let mut buf = vec![0u8; MAX_MESSAGE_LEN];
    let mut payload = vec![0u8; MAX_MESSAGE_LEN];
    loop {
        let left = deadline.saturating_duration_since(tokio::time::Instant::now());
        let msg = relay.recv(left).await.map_err(|e| if e == "timed out" { "the pairing code expired".to_string() } else { e })?;
        let mut hs = noise::pairing_responder(&config.noise_private()).map_err(|e| e.to_string())?;
        let Ok(n) = hs.read_message(&msg.data, &mut payload) else { continue };
        let Ok(hello) = serde_json::from_slice::<PairingHello>(&payload[..n]) else { continue };
        let got = B64.decode(&hello.secret).unwrap_or_default();
        if !noise::pairing_secret_matches(&secret, &got) || msg.from != hello.phone_id {
            continue;
        }
        let welcome = serde_json::to_vec(&PairingWelcome { name: pc_name() }).unwrap();
        let n = hs.write_message(&welcome, &mut buf).map_err(|e| e.to_string())?;
        relay.send_to(&hello.phone_id, &msg.sid, &buf[..n]).await?;

        let phone_key = hs.get_remote_static().ok_or("phone sent no key")?.to_vec();
        on_event(PairEvent::Confirm { phone_name: hello.name.clone(), code: noise::confirmation_code(hs.get_handshake_hash()) });

        let mut transport = hs.into_transport_mode().map_err(|e| e.to_string())?;
        let reply = relay
            .recv_session(&msg.sid, Duration::from_secs(120))
            .await
            .map_err(|e| format!("no confirmation from the phone ({e})"))?;
        let n = transport.read_message(&reply.data, &mut payload).map_err(|e| e.to_string())?;
        let done: PairingDone = serde_json::from_slice(&payload[..n]).map_err(|e| e.to_string())?;
        if !done.accepted {
            return Err("pairing was cancelled on the phone".into());
        }
        let phone = Phone { id: hello.phone_id, name: hello.name, noise_public: B64.encode(phone_key), paired_at: now() };
        // One phone per PC for now: a new pairing replaces the old one.
        for old in config.phones().iter().map(|p| p.id.clone()).collect::<Vec<_>>() {
            config.remove_phone(&old);
        }
        config.add_phone(phone.clone());
        config.save()?;
        return Ok(phone);
    }
}

/// A code from the phone.
#[derive(Debug, Clone)]
pub struct Code {
    pub code: String,
    /// Unix seconds when it stops working.
    pub valid_until: u64,
}

/// An encrypted session with the phone. The phone closes it after ~8 s without requests, so
/// keep one only for a burst of requests.
pub struct Session {
    relay: RelayClient,
    transport: noise::TransportState,
    pub phone: Phone,
    sid: String,
}

impl Session {
    pub async fn open(config: &Config) -> Result<Self, String> {
        let relay_url = config.relay_url().ok_or("no relay configured")?.to_string();
        Self::open_via(config, &relay_url).await
    }

    /// Like [`Session::open`], but reaching the relay at `relay_url` (e.g. the desktop app's own
    /// relay over loopback, which keeps working when the PC's network address changes).
    pub async fn open_via(config: &Config, relay_url: &str) -> Result<Self, String> {
        let phone = config.phones().first().cloned().ok_or("no phone paired yet")?;
        let mut relay = RelayClient::connect(relay_url, &config.signing_key()).await?;
        let sid = random_id();
        let mut hs = noise::session_initiator(&config.noise_private(), &phone.noise_public()).map_err(|e| e.to_string())?;
        let mut buf = vec![0u8; MAX_MESSAGE_LEN];
        let n = hs.write_message(&[], &mut buf).map_err(|e| e.to_string())?;
        relay.send_to(&phone.id, &sid, &buf[..n]).await?;
        let reply = relay.recv_session(&sid, PHONE_TIMEOUT).await.map_err(|e| {
            format!("{} didn't answer ({e}). Is Totem allowed network access, and not force-stopped?", phone.name)
        })?;
        hs.read_message(&reply.data, &mut buf).map_err(|_| "the phone's reply failed verification (pair again?)".to_string())?;
        let transport = hs.into_transport_mode().map_err(|e| e.to_string())?;
        Ok(Self { relay, transport, phone, sid })
    }

    async fn request(&mut self, req: &PcToPhone, timeout: Duration) -> Result<PhoneToPc, String> {
        let mut buf = vec![0u8; MAX_MESSAGE_LEN];
        let n = self.transport.write_message(&serde_json::to_vec(req).unwrap(), &mut buf).map_err(|e| e.to_string())?;
        self.relay.send_to(&self.phone.id, &self.sid, &buf[..n]).await?;
        let reply = self.relay.recv_session(&self.sid, timeout).await.map_err(|e| format!("no answer from the phone ({e})"))?;
        let n = self.transport.read_message(&reply.data, &mut buf).map_err(|e| e.to_string())?;
        serde_json::from_slice(&buf[..n]).map_err(|e| e.to_string())
    }

    pub async fn accounts(&mut self) -> Result<Vec<AccountInfo>, String> {
        match self.request(&PcToPhone::ListAccounts { req: random_id(), ts: now() }, PHONE_TIMEOUT).await? {
            PhoneToPc::Accounts { items, .. } => Ok(items),
            PhoneToPc::Denied { reason, .. } => Err(format!("phone refused: {reason}")),
            other => Err(format!("unexpected reply: {other:?}")),
        }
    }

    /// Asks for a code. For "ask first" accounts this waits for the tap on the phone.
    pub async fn code(&mut self, account_id: &str) -> Result<Code, String> {
        let req = PcToPhone::GetCode { req: random_id(), ts: now(), account: account_id.to_string() };
        match self.request(&req, APPROVAL_TIMEOUT).await? {
            // Under 5 seconds left: the phone also sent the next code, which is more useful.
            PhoneToPc::Code { next_code: Some(next), valid_until, .. } => Ok(Code { code: next, valid_until: valid_until + 30 }),
            PhoneToPc::Code { code, valid_until, .. } => Ok(Code { code, valid_until }),
            PhoneToPc::Denied { reason, .. } => Err(match reason.as_str() {
                "user_denied" => "denied on the phone".into(),
                "policy_never" => "this account is set to phone-only".into(),
                "approval_timeout" => "nobody approved on the phone in time".into(),
                "paused" => "the phone paused this PC after too many requests; try again in 15 minutes".into(),
                "unknown_account" => "that account is no longer on the phone".into(),
                other => format!("phone refused: {other}"),
            }),
            other => Err(format!("unexpected reply: {other:?}")),
        }
    }
}
