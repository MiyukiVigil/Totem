//! Sends empty "wake up" pushes through FCM HTTP v1 with a service-account key.

use serde::{Deserialize, Serialize};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tokio::sync::Mutex;

#[derive(Deserialize)]
struct ServiceAccount {
    project_id: String,
    client_email: String,
    private_key: String,
    token_uri: String,
}

#[derive(Serialize)]
struct Claims<'a> {
    iss: &'a str,
    scope: &'a str,
    aud: &'a str,
    iat: u64,
    exp: u64,
}

pub struct Fcm {
    account: ServiceAccount,
    key: jsonwebtoken::EncodingKey,
    http: reqwest::Client,
    token: Mutex<Option<(String, Instant)>>,
}

impl Fcm {
    pub fn from_key_file(path: &std::path::Path) -> Result<Self, String> {
        let text = std::fs::read_to_string(path).map_err(|e| format!("reading {}: {e}", path.display()))?;
        let account: ServiceAccount = serde_json::from_str(&text).map_err(|e| format!("parsing service-account key: {e}"))?;
        let key = jsonwebtoken::EncodingKey::from_rsa_pem(account.private_key.as_bytes()).map_err(|e| e.to_string())?;
        Ok(Self { account, key, http: reqwest::Client::new(), token: Mutex::new(None) })
    }

    async fn access_token(&self) -> Result<String, String> {
        let mut cached = self.token.lock().await;
        if let Some((token, at)) = cached.as_ref() {
            if at.elapsed() < Duration::from_secs(50 * 60) {
                return Ok(token.clone());
            }
        }
        let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
        let claims = Claims {
            iss: &self.account.client_email,
            scope: "https://www.googleapis.com/auth/firebase.messaging",
            aud: &self.account.token_uri,
            iat: now,
            exp: now + 3600,
        };
        let jwt = jsonwebtoken::encode(&jsonwebtoken::Header::new(jsonwebtoken::Algorithm::RS256), &claims, &self.key)
            .map_err(|e| e.to_string())?;
        #[derive(Deserialize)]
        struct TokenResponse {
            access_token: String,
        }
        let res = self
            .http
            .post(&self.account.token_uri)
            .form(&[("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"), ("assertion", jwt.as_str())])
            .send()
            .await
            .map_err(|e| e.to_string())?;
        if !res.status().is_success() {
            return Err(format!("token endpoint: {} {}", res.status(), res.text().await.unwrap_or_default()));
        }
        let token = res.json::<TokenResponse>().await.map_err(|e| e.to_string())?.access_token;
        *cached = Some((token.clone(), Instant::now()));
        Ok(token)
    }

    /// High-priority data message with no content beyond "wake".
    pub async fn wake(&self, fcm_token: &str) -> Result<(), String> {
        let bearer = self.access_token().await?;
        let body = serde_json::json!({
            "message": {
                "token": fcm_token,
                "data": { "kind": "wake" },
                "android": { "priority": "HIGH", "ttl": "15s" }
            }
        });
        let url = format!("https://fcm.googleapis.com/v1/projects/{}/messages:send", self.account.project_id);
        let res = self.http.post(url).bearer_auth(bearer).json(&body).send().await.map_err(|e| e.to_string())?;
        if !res.status().is_success() {
            return Err(format!("FCM send: {} {}", res.status(), res.text().await.unwrap_or_default()));
        }
        Ok(())
    }
}
