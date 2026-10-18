//! This PC's keys and paired phones.
//!
//! - Private keys: Windows Credential Manager (or the macOS / Linux keychain), entry
//!   `Totem` / `pc-keys`. Never written to disk in plain form.
//! - Everything else (relay URL, paired phones, cached account names): `%APPDATA%\Totem\config.json`.
//!
//! Optionally the private keys are encrypted with a key only Windows Hello can produce
//! ([`Config::protect_with_hello`], see `hello_key.rs`); they're then decrypted once per process.
//!
//! Setting `TOTEM_CONFIG_DIR` keeps everything, keys included, in that directory instead. That's
//! for tests and development only, so they never touch the real pairing.

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use ed25519_dalek::SigningKey;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use totem_protocol::app::AccountInfo;
use totem_protocol::{noise, relay};

const KEYRING_SERVICE: &str = "Totem";
const KEYRING_USER: &str = "pc-keys";

/// What's in the key store: plain keys, or keys encrypted for Windows Hello (with the public
/// halves alongside, so ids can be shown without unlocking).
#[derive(Serialize, Deserialize, Clone)]
#[serde(untagged)]
enum Stored {
    Plain(Keys),
    Hello(HelloWrapped),
}

#[derive(Serialize, Deserialize, Clone)]
struct HelloWrapped {
    /// base64(nonce (12) | ChaCha20-Poly1305 of the `Keys` JSON).
    hello_wrapped: String,
    relay_public: String,
    noise_public: String,
}

const WRAP_AAD: &[u8] = b"totem-pc-keys-v1";

/// Keys unlocked with Windows Hello in this process, by relay public key.
static UNLOCKED: std::sync::Mutex<Option<(String, Keys)>> = std::sync::Mutex::new(None);

#[derive(Serialize, Deserialize, Clone)]
struct Keys {
    /// Ed25519 key for logging in to the relay (base64).
    relay_key: String,
    /// Noise static key pair (base64).
    noise_private: String,
    noise_public: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Phone {
    pub id: String,
    pub name: String,
    /// Phone's Noise static public key (base64).
    pub noise_public: String,
    pub paired_at: u64,
}

impl Phone {
    pub fn noise_public(&self) -> Vec<u8> {
        B64.decode(&self.noise_public).expect("stored key is valid base64")
    }
}

#[derive(Serialize, Deserialize, Default)]
struct Settings {
    relay_url: Option<String>,
    #[serde(default)]
    phones: Vec<Phone>,
    /// Account names from the last successful list, so the search box can show them instantly.
    #[serde(default)]
    account_cache: Vec<AccountInfo>,
    /// Desktop app: run the relay inside the app (home setup). Default on.
    #[serde(default)]
    host_relay: Option<bool>,
    /// Desktop app: ask for Windows Hello before copying a code. Default on.
    #[serde(default)]
    require_hello: Option<bool>,
}

enum KeyStore {
    Keyring,
    File(PathBuf),
}

impl KeyStore {
    fn load(&self) -> Result<Option<Stored>, String> {
        let text = match self {
            KeyStore::Keyring => match keyring_entry()?.get_password() {
                Ok(t) => t,
                Err(keyring::Error::NoEntry) => return Ok(None),
                Err(e) => return Err(format!("reading keys from Credential Manager: {e}")),
            },
            KeyStore::File(p) => match std::fs::read_to_string(p) {
                Ok(t) => t,
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
                Err(e) => return Err(e.to_string()),
            },
        };
        serde_json::from_str(&text).map(Some).map_err(|e| format!("stored keys are damaged: {e}"))
    }

    fn save(&self, keys: &Stored) -> Result<(), String> {
        let text = serde_json::to_string(keys).unwrap();
        match self {
            KeyStore::Keyring => keyring_entry()?.set_password(&text).map_err(|e| format!("saving keys to Credential Manager: {e}")),
            KeyStore::File(p) => std::fs::write(p, text).map_err(|e| e.to_string()),
        }
    }

    fn delete(&self) {
        match self {
            KeyStore::Keyring => {
                if let Ok(e) = keyring_entry() {
                    let _ = e.delete_credential();
                }
            }
            KeyStore::File(p) => {
                let _ = std::fs::remove_file(p);
            }
        }
    }
}

fn keyring_entry() -> Result<keyring::Entry, String> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER).map_err(|e| e.to_string())
}

pub struct Config {
    dir: PathBuf,
    keys: Stored,
    key_store: KeyStore,
    settings: Settings,
}

impl Config {
    pub fn dir() -> PathBuf {
        match std::env::var_os("TOTEM_CONFIG_DIR") {
            Some(d) => PathBuf::from(d),
            None => dirs::config_dir().expect("no config directory").join("Totem"),
        }
    }

    /// Loads the config, creating this PC's keys on first run.
    pub fn load_or_create() -> Result<Self, String> {
        let dir = Self::dir();
        let key_store = if std::env::var_os("TOTEM_CONFIG_DIR").is_some() { KeyStore::File(dir.join("keys.json")) } else { KeyStore::Keyring };
        Self::load_from(dir, key_store)
    }

    fn load_from(dir: PathBuf, key_store: KeyStore) -> Result<Self, String> {
        std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
        let settings_path = dir.join("config.json");
        let settings: Settings = match std::fs::read_to_string(&settings_path) {
            Ok(t) => serde_json::from_str(&t).map_err(|e| format!("{} is damaged: {e}", settings_path.display()))?,
            Err(_) => Settings::default(),
        };
        let keys = match key_store.load()? {
            Some(k) => k,
            None => {
                let signing = SigningKey::generate(&mut rand::rngs::OsRng);
                let kp = noise::generate_keypair().map_err(|e| e.to_string())?;
                let k = Keys {
                    relay_key: B64.encode(signing.to_bytes()),
                    noise_private: B64.encode(&kp.private),
                    noise_public: B64.encode(&kp.public),
                };
                let stored = Stored::Plain(k);
                key_store.save(&stored)?;
                stored
            }
        };
        let config = Self { dir, keys, key_store, settings };
        config.save()?;
        Ok(config)
    }

    pub fn save(&self) -> Result<(), String> {
        let path = self.dir.join("config.json");
        let tmp = path.with_extension("tmp");
        std::fs::write(&tmp, serde_json::to_string_pretty(&self.settings).unwrap()).map_err(|e| e.to_string())?;
        std::fs::rename(&tmp, &path).map_err(|e| e.to_string())
    }

    /// Deletes this PC's keys and pairings. A new identity is created on next load.
    pub fn reset(self) -> Result<(), String> {
        self.key_store.delete();
        std::fs::remove_file(self.dir.join("config.json")).map_err(|e| e.to_string())
    }

    /// The private keys, unlocking them with Windows Hello first if they're protected (blocks
    /// on the prompt; once per process).
    fn private_keys(&self) -> Result<Keys, String> {
        self.private_keys_with(crate::hello_key::derive)
    }

    fn private_keys_with(&self, derive: impl Fn(bool) -> Result<[u8; 32], String>) -> Result<Keys, String> {
        let w = match &self.keys {
            Stored::Plain(k) => return Ok(k.clone()),
            Stored::Hello(w) => w,
        };
        if let Some((id, keys)) = UNLOCKED.lock().unwrap().as_ref() {
            if *id == w.relay_public {
                return Ok(keys.clone());
            }
        }
        let keys = unwrap_keys(w, &derive(false)?)?;
        *UNLOCKED.lock().unwrap() = Some((w.relay_public.clone(), keys.clone()));
        Ok(keys)
    }

    /// Unlocks now (e.g. off the UI thread before keys are needed). No-op for plain keys.
    pub fn unlock(&self) -> Result<(), String> {
        self.private_keys().map(|_| ())
    }

    pub fn is_protected(&self) -> bool {
        matches!(self.keys, Stored::Hello(_))
    }

    /// True if the private keys can be used without a prompt.
    pub fn is_unlocked(&self) -> bool {
        match &self.keys {
            Stored::Plain(_) => true,
            Stored::Hello(w) => UNLOCKED.lock().unwrap().as_ref().is_some_and(|(id, _)| *id == w.relay_public),
        }
    }

    /// Encrypts the private keys for Windows Hello. Asks twice, and only switches over if both
    /// answers give the same key and it decrypts what it encrypted.
    pub fn protect_with_hello(&mut self) -> Result<(), String> {
        self.protect_with(crate::hello_key::derive)
    }

    fn protect_with(&mut self, derive: impl Fn(bool) -> Result<[u8; 32], String>) -> Result<(), String> {
        let Stored::Plain(keys) = self.keys.clone() else { return Ok(()) };
        let first = derive(true)?;
        let second = derive(false)?;
        if first != second {
            return Err("Windows Hello gave a different key each time on this PC, so Totem can't use it to protect keys. Nothing was changed.".into());
        }
        let wrapped = wrap_keys(&keys, &first)?;
        let back = unwrap_keys(&wrapped, &second)?;
        if back.relay_key != keys.relay_key || back.noise_private != keys.noise_private {
            return Err("checking the encrypted keys failed; nothing was changed".into());
        }
        self.key_store.save(&Stored::Hello(wrapped.clone()))?;
        *UNLOCKED.lock().unwrap() = Some((wrapped.relay_public.clone(), keys));
        self.keys = Stored::Hello(wrapped);
        Ok(())
    }

    /// Back to plain keys in Credential Manager (unlocks first).
    pub fn unprotect(&mut self) -> Result<(), String> {
        let stored = Stored::Plain(self.private_keys()?);
        self.key_store.save(&stored)?;
        self.keys = stored;
        Ok(())
    }

    pub fn signing_key(&self) -> Result<SigningKey, String> {
        let keys = self.private_keys()?;
        let bytes: [u8; 32] = B64.decode(&keys.relay_key).map_err(|e| e.to_string())?.try_into().map_err(|_| "bad stored key")?;
        Ok(SigningKey::from_bytes(&bytes))
    }

    pub fn device_id(&self) -> String {
        let public = match &self.keys {
            Stored::Plain(k) => {
                let bytes: [u8; 32] = B64.decode(&k.relay_key).unwrap().try_into().expect("32-byte key");
                SigningKey::from_bytes(&bytes).verifying_key().to_bytes().to_vec()
            }
            Stored::Hello(w) => B64.decode(&w.relay_public).unwrap(),
        };
        relay::device_id(&public)
    }

    pub fn noise_private(&self) -> Result<Vec<u8>, String> {
        B64.decode(&self.private_keys()?.noise_private).map_err(|e| e.to_string())
    }

    pub fn noise_public(&self) -> Vec<u8> {
        let public = match &self.keys {
            Stored::Plain(k) => &k.noise_public,
            Stored::Hello(w) => &w.noise_public,
        };
        B64.decode(public).unwrap()
    }

    pub fn relay_url(&self) -> Option<&str> {
        self.settings.relay_url.as_deref()
    }

    pub fn set_relay_url(&mut self, url: String) {
        self.settings.relay_url = Some(url);
    }

    pub fn phones(&self) -> &[Phone] {
        &self.settings.phones
    }

    pub fn add_phone(&mut self, phone: Phone) {
        self.settings.phones.retain(|p| p.id != phone.id);
        self.settings.phones.push(phone);
    }

    /// Removes phones whose name or id matches; returns how many were removed.
    pub fn remove_phone(&mut self, name_or_id: &str) -> usize {
        let before = self.settings.phones.len();
        self.settings.phones.retain(|p| !p.name.eq_ignore_ascii_case(name_or_id) && p.id != name_or_id);
        if self.settings.phones.is_empty() {
            self.settings.account_cache.clear();
        }
        before - self.settings.phones.len()
    }

    pub fn host_relay(&self) -> bool {
        self.settings.host_relay.unwrap_or(true)
    }

    pub fn set_host_relay(&mut self, on: bool) {
        self.settings.host_relay = Some(on);
    }

    pub fn require_hello(&self) -> bool {
        self.settings.require_hello.unwrap_or(true)
    }

    pub fn set_require_hello(&mut self, on: bool) {
        self.settings.require_hello = Some(on);
    }

    pub fn account_cache(&self) -> &[AccountInfo] {
        &self.settings.account_cache
    }

    pub fn set_account_cache(&mut self, accounts: Vec<AccountInfo>) {
        self.settings.account_cache = accounts;
    }
}

fn wrap_keys(keys: &Keys, key: &[u8; 32]) -> Result<HelloWrapped, String> {
    use chacha20poly1305::aead::{Aead, KeyInit, Payload};
    let cipher = chacha20poly1305::ChaCha20Poly1305::new(key.into());
    let nonce: [u8; 12] = rand::random();
    let plain = serde_json::to_vec(keys).unwrap();
    let ct = cipher.encrypt(&nonce.into(), Payload { msg: &plain, aad: WRAP_AAD }).map_err(|_| "encrypting keys failed")?;
    let relay_bytes: [u8; 32] = B64.decode(&keys.relay_key).map_err(|e| e.to_string())?.try_into().map_err(|_| "bad stored key")?;
    Ok(HelloWrapped {
        hello_wrapped: B64.encode([nonce.as_slice(), &ct].concat()),
        relay_public: B64.encode(SigningKey::from_bytes(&relay_bytes).verifying_key().as_bytes()),
        noise_public: keys.noise_public.clone(),
    })
}

fn unwrap_keys(w: &HelloWrapped, key: &[u8; 32]) -> Result<Keys, String> {
    use chacha20poly1305::aead::{Aead, KeyInit, Payload};
    let blob = B64.decode(&w.hello_wrapped).map_err(|e| e.to_string())?;
    if blob.len() < 12 + 16 {
        return Err("stored keys are damaged".into());
    }
    let cipher = chacha20poly1305::ChaCha20Poly1305::new(key.into());
    let plain = cipher
        .decrypt(blob[..12].into(), Payload { msg: &blob[12..], aad: WRAP_AAD })
        .map_err(|_| "Windows Hello unlocked, but the keys didn't decrypt (was Windows Hello set up again?)")?;
    serde_json::from_slice(&plain).map_err(|e| format!("stored keys are damaged: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_persist_and_settings_round_trip() {
        let dir = std::env::temp_dir().join(format!("totem-config-test-{}", rand::random::<u64>()));
        let store = || KeyStore::File(dir.join("keys.json"));
        let mut c = Config::load_from(dir.clone(), store()).unwrap();
        let id = c.device_id();
        c.set_relay_url("ws://x/v1/ws".into());
        c.add_phone(Phone { id: "p".into(), name: "Phone".into(), noise_public: B64.encode([1u8; 32]), paired_at: 1 });
        c.save().unwrap();

        let c2 = Config::load_from(dir.clone(), store()).unwrap();
        assert_eq!(c2.device_id(), id, "same keys after reload");
        assert_eq!(c2.relay_url(), Some("ws://x/v1/ws"));
        assert_eq!(c2.phones().len(), 1);
        // Private keys never land in config.json.
        let settings = std::fs::read_to_string(dir.join("config.json")).unwrap();
        let Stored::Plain(k) = &c2.keys else { panic!("expected plain keys") };
        assert!(!settings.contains(&k.noise_private) && !settings.contains("relay_key"));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn hello_protection_round_trip() {
        let dir = std::env::temp_dir().join(format!("totem-config-test-{}", rand::random::<u64>()));
        let store = || KeyStore::File(dir.join("keys.json"));
        let fake_hello = |_create: bool| Ok([7u8; 32]);
        let mut c = Config::load_from(dir.clone(), store()).unwrap();
        let (id, noise_pub, signing) = (c.device_id(), c.noise_public(), c.signing_key().unwrap().to_bytes());
        c.protect_with(fake_hello).unwrap();

        // On disk: no private key material in the clear.
        let on_disk = std::fs::read_to_string(dir.join("keys.json")).unwrap();
        assert!(on_disk.contains("hello_wrapped") && !on_disk.contains("relay_key"), "{on_disk}");

        // Reloaded: public parts without unlocking; private parts after.
        *UNLOCKED.lock().unwrap() = None;
        let c2 = Config::load_from(dir.clone(), store()).unwrap();
        assert!(c2.is_protected() && !c2.is_unlocked());
        assert_eq!(c2.device_id(), id);
        assert_eq!(c2.noise_public(), noise_pub);
        assert_eq!(c2.private_keys_with(fake_hello).unwrap().relay_key, B64.encode(signing));
        assert!(c2.is_unlocked());

        // A different Windows Hello key can't decrypt them.
        *UNLOCKED.lock().unwrap() = None;
        assert!(c2.private_keys_with(|_| Ok([8u8; 32])).is_err());

        // Refuses to switch if Windows Hello isn't deterministic.
        let dir2 = std::env::temp_dir().join(format!("totem-config-test-{}", rand::random::<u64>()));
        let mut c3 = Config::load_from(dir2.clone(), KeyStore::File(dir2.join("keys.json"))).unwrap();
        assert!(c3.protect_with(|create| Ok(if create { [1u8; 32] } else { [2u8; 32] })).is_err());
        assert!(!c3.is_protected());
        std::fs::remove_dir_all(dir).unwrap();
        std::fs::remove_dir_all(dir2).unwrap();
    }
}
