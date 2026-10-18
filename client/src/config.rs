//! This PC's keys and paired phones.
//!
//! - Private keys: Windows Credential Manager (or the macOS / Linux keychain), entry
//!   `Totem` / `pc-keys`. Never written to disk in plain form.
//! - Everything else (relay URL, paired phones, cached account names): `%APPDATA%\Totem\config.json`.
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
}

enum KeyStore {
    Keyring,
    File(PathBuf),
}

impl KeyStore {
    fn load(&self) -> Result<Option<Keys>, String> {
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

    fn save(&self, keys: &Keys) -> Result<(), String> {
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
    keys: Keys,
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
                key_store.save(&k)?;
                k
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

    pub fn signing_key(&self) -> SigningKey {
        let bytes: [u8; 32] = B64.decode(&self.keys.relay_key).unwrap().try_into().expect("32-byte key");
        SigningKey::from_bytes(&bytes)
    }

    pub fn device_id(&self) -> String {
        relay::device_id(self.signing_key().verifying_key().as_bytes())
    }

    pub fn noise_private(&self) -> Vec<u8> {
        B64.decode(&self.keys.noise_private).unwrap()
    }

    pub fn noise_public(&self) -> Vec<u8> {
        B64.decode(&self.keys.noise_public).unwrap()
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

    pub fn account_cache(&self) -> &[AccountInfo] {
        &self.settings.account_cache
    }

    pub fn set_account_cache(&mut self, accounts: Vec<AccountInfo>) {
        self.settings.account_cache = accounts;
    }
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
        assert!(!settings.contains(&c2.keys.noise_private) && !settings.contains("relay_key"));
        std::fs::remove_dir_all(dir).unwrap();
    }
}
