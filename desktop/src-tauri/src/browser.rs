//! Bridge to the Totem browser extension (Chrome, Edge, Brave), over native messaging.
//!
//! The browser starts this same exe as its native messaging host, with the extension's origin as
//! the first argument (`totem-desktop.exe chrome-extension://<id>/ --parent-window=<hwnd>`). In
//! that mode [`run_host`] forwards each message over a per-user named pipe to the tray app and
//! writes the answer back; it never touches keys itself. The tray app answers in [`serve`], with
//! the same Windows Hello check and phone policies as Ctrl+Shift+A.

use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::OnceLock;
use tauri::{AppHandle, Manager};
use totem_client::config::Config;
use totem_client::AccountInfo;

use crate::AppState;

/// Native messaging host name, as the extension calls it.
pub const HOST_NAME: &str = "tech.miyukivigil.totem";
/// The extension's fixed ID (from the `key` in browser-extension/manifest.json). Only it may
/// start the host.
pub const EXTENSION_ORIGIN: &str = "chrome-extension://mlfmkiabdgpmmnmgojbamobimfgkoflf/";
/// Native messaging caps messages from the browser at 4 GB; ours are tiny.
const MAX_MESSAGE: u32 = 64 * 1024;

fn pipe_name() -> String {
    let user = std::env::var("USERNAME").unwrap_or_else(|_| "user".into());
    format!(r"\\.\pipe\totem-browser-{user}")
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Request {
    Status,
    Accounts {
        /// Ask the phone for the current list instead of answering from the cache.
        #[serde(default)]
        refresh: bool,
    },
    Code {
        account: String,
        /// The browser window, so the Windows Hello prompt shows on top of it (added by the host).
        #[serde(default)]
        parent_window: isize,
    },
}

// ---- Tray app side ----

/// Points Chrome, Edge and Brave at this exe as the native messaging host. Runs at every start, so
/// it follows the app when it's reinstalled somewhere else.
#[cfg(windows)]
pub fn register() -> Result<(), String> {
    use windows::core::{HSTRING, PCWSTR};
    use windows::Win32::System::Registry::{RegSetKeyValueW, HKEY_CURRENT_USER, REG_SZ};

    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let manifest = json!({
        "name": HOST_NAME,
        "description": "Totem: 2FA codes from your phone",
        "path": exe,
        "type": "stdio",
        "allowed_origins": [EXTENSION_ORIGIN],
    });
    let path = Config::dir().join("browser-host.json");
    std::fs::create_dir_all(Config::dir()).map_err(|e| e.to_string())?;
    std::fs::write(&path, serde_json::to_vec_pretty(&manifest).unwrap()).map_err(|e| e.to_string())?;

    let value: Vec<u16> = path.to_string_lossy().encode_utf16().chain([0]).collect();
    for browser in [r"Google\Chrome", r"Microsoft\Edge", r"BraveSoftware\Brave-Browser", "Chromium"] {
        let key = HSTRING::from(format!(r"Software\{browser}\NativeMessagingHosts\{HOST_NAME}"));
        let err = unsafe {
            RegSetKeyValueW(HKEY_CURRENT_USER, &key, PCWSTR::null(), REG_SZ.0, Some(value.as_ptr().cast()), (value.len() * 2) as u32)
        };
        if err.is_err() {
            return Err(format!("couldn't register with {browser}: {err:?}"));
        }
    }
    Ok(())
}

#[cfg(not(windows))]
pub fn register() -> Result<(), String> {
    Ok(())
}

/// Answers the browser extension's requests (forwarded by [`run_host`]) for as long as the app runs.
#[cfg(windows)]
pub fn serve(app: AppHandle) {
    use tokio::net::windows::named_pipe::ServerOptions;

    tauri::async_runtime::spawn(async move {
        let name = pipe_name();
        // first_pipe_instance: fail rather than share the name with a pipe someone else made.
        let mut server = match ServerOptions::new().first_pipe_instance(true).create(&name) {
            Ok(s) => s,
            Err(e) => return tracing::warn!("browser extension bridge unavailable: {e}"),
        };
        loop {
            let connected = server.connect().await;
            let next = match ServerOptions::new().create(&name) {
                Ok(s) => s,
                Err(e) => return tracing::warn!("browser extension bridge stopped: {e}"),
            };
            let client = std::mem::replace(&mut server, next);
            if connected.is_ok() {
                let app = app.clone();
                tauri::async_runtime::spawn(async move { handle(app, client).await });
            }
        }
    });
}

#[cfg(not(windows))]
pub fn serve(_app: AppHandle) {}

#[cfg(windows)]
async fn handle(app: AppHandle, pipe: tokio::net::windows::named_pipe::NamedPipeServer) {
    use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};

    let (read, mut write) = tokio::io::split(pipe);
    let mut line = String::new();
    let reply = match BufReader::new(read.take(MAX_MESSAGE as u64)).read_line(&mut line).await {
        Ok(_) => match serde_json::from_str::<Request>(&line) {
            Ok(req) => answer(&app, req).await,
            Err(e) => Err(format!("bad request: {e}")),
        },
        Err(e) => Err(e.to_string()),
    };
    let body = reply.unwrap_or_else(|e| json!({ "ok": false, "error": e }));
    let _ = write.write_all(format!("{body}\n").as_bytes()).await;
}

async fn answer(app: &AppHandle, req: Request) -> Result<Value, String> {
    let state = app.state::<AppState>();
    match req {
        Request::Status => {
            let config = Config::load_or_create()?;
            Ok(json!({ "ok": true, "paired": !config.phones().is_empty(), "phone": config.phones().first().map(|p| p.name.clone()) }))
        }
        Request::Accounts { refresh } => {
            let cached = Config::load_or_create()?.account_cache().to_vec();
            let accounts = if refresh || cached.is_empty() { crate::fetch_accounts(&state).await? } else { cached };
            let logos = logos(app);
            Ok(json!({ "ok": true, "accounts": accounts.iter().map(|a| account_json(a, logos)).collect::<Vec<_>>() }))
        }
        Request::Code { account, parent_window } => {
            crate::check_hello(&state, parent_window, "Unlock Totem to fill a 2FA code in your browser").await?;
            let code = crate::with_session(&state, move |s| Box::pin(async move { s.code(&account).await })).await?;
            Ok(json!({ "ok": true, "code": code.code, "seconds_left": code.valid_until.saturating_sub(totem_client::now()) }))
        }
    }
}

fn account_json(a: &AccountInfo, logos: &HashMap<String, Value>) -> Value {
    let (title, subtitle) = if a.issuer.is_empty() { (a.label.as_str(), "") } else { (a.issuer.as_str(), a.label.as_str()) };
    json!({
        "id": a.id,
        "issuer": a.issuer,
        "title": title,
        "subtitle": subtitle,
        "policy": a.policy,
        "icon": a.icon,
        "logo": a.icon.as_ref().and_then(|slug| logos.get(slug)),
    })
}

/// Brand logos by slug, from the bundled ui/brand-icons.json (so the extension needn't ship 4 MB).
fn logos(app: &AppHandle) -> &'static HashMap<String, Value> {
    static LOGOS: OnceLock<HashMap<String, Value>> = OnceLock::new();
    LOGOS.get_or_init(|| {
        let Some(asset) = app.asset_resolver().get("brand-icons.json".into()) else { return HashMap::new() };
        let Ok(data) = serde_json::from_slice::<Value>(&asset.bytes) else { return HashMap::new() };
        data["icons"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|i| Some((i["s"].as_str()?.to_string(), json!({ "h": i["h"], "p": i["p"], "t": i["t"] }))))
            .collect()
    })
}

// ---- Native messaging host side (started by the browser) ----

/// True when the browser started this exe as its native messaging host.
pub fn launched_by_browser() -> bool {
    std::env::args().nth(1).is_some_and(|a| a.starts_with("chrome-extension://"))
}

/// Relays messages between the browser (stdin/stdout, length-prefixed JSON) and the tray app,
/// until the browser closes the connection.
pub fn run_host() {
    let args: Vec<String> = std::env::args().collect();
    let allowed = args.get(1).map(String::as_str) == Some(EXTENSION_ORIGIN);
    let parent_window = args.iter().find_map(|a| a.strip_prefix("--parent-window=")).and_then(|v| v.parse::<i64>().ok()).unwrap_or(0);
    let mut stdin = std::io::stdin().lock();
    let mut stdout = std::io::stdout().lock();
    while let Some(msg) = read_message(&mut stdin) {
        let reply = match msg {
            _ if !allowed => Err("this extension isn't allowed to use Totem".to_string()),
            Ok(mut req) => {
                if let Some(obj) = req.as_object_mut() {
                    obj.insert("parent_window".into(), parent_window.into());
                }
                forward(&req)
            }
            Err(e) => Err(e),
        };
        let reply = reply.unwrap_or_else(|e| json!({ "ok": false, "error": e }));
        if write_message(&mut stdout, &reply).is_err() {
            break;
        }
    }
}

/// Reads one native message; `None` at end of input.
fn read_message(input: &mut impl std::io::Read) -> Option<Result<Value, String>> {
    let mut len = [0u8; 4];
    input.read_exact(&mut len).ok()?;
    let len = u32::from_ne_bytes(len);
    if len > MAX_MESSAGE {
        return None;
    }
    let mut buf = vec![0u8; len as usize];
    input.read_exact(&mut buf).ok()?;
    Some(serde_json::from_slice(&buf).map_err(|e| format!("bad message: {e}")))
}

fn write_message(out: &mut impl std::io::Write, msg: &Value) -> std::io::Result<()> {
    let body = serde_json::to_vec(msg).unwrap();
    out.write_all(&(body.len() as u32).to_ne_bytes())?;
    out.write_all(&body)?;
    out.flush()
}

fn forward(req: &Value) -> Result<Value, String> {
    use std::io::{BufRead, BufReader, Write};

    let pipe = open_pipe()?;
    writeln!(&pipe, "{req}").map_err(|e| format!("Totem: {e}"))?;
    let mut line = String::new();
    BufReader::new(&pipe).read_line(&mut line).map_err(|e| format!("Totem: {e}"))?;
    serde_json::from_str(&line).map_err(|_| "Totem closed the connection".to_string())
}

/// Connects to the tray app, starting it if it isn't running.
fn open_pipe() -> Result<std::fs::File, String> {
    const ERROR_PIPE_BUSY: i32 = 231;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    let mut launched = false;
    loop {
        match std::fs::OpenOptions::new().read(true).write(true).open(pipe_name()) {
            Ok(f) => return Ok(f),
            Err(e) if e.raw_os_error() == Some(ERROR_PIPE_BUSY) => {}
            Err(_) if !launched => {
                launched = true;
                launch_app();
            }
            Err(_) => {}
        }
        if std::time::Instant::now() > deadline {
            return Err("Totem isn't running on this PC. Open it from the Start menu.".into());
        }
        std::thread::sleep(std::time::Duration::from_millis(200));
    }
}

/// Starts the tray app on its own, so it outlives this host (which the browser ends after each
/// message).
fn launch_app() {
    let Ok(exe) = std::env::current_exe() else { return };
    let spawn = |flags: u32| {
        let mut cmd = std::process::Command::new(&exe);
        cmd.arg("--background").stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(flags);
        }
        let _ = flags;
        cmd.spawn()
    };
    const DETACHED_PROCESS: u32 = 0x0000_0008;
    const CREATE_BREAKAWAY_FROM_JOB: u32 = 0x0100_0000;
    // Browsers run hosts in a job object; leave it if allowed, so closing the browser doesn't
    // close Totem.
    if spawn(DETACHED_PROCESS | CREATE_BREAKAWAY_FROM_JOB).is_err() {
        let _ = spawn(DETACHED_PROCESS);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_messages_round_trip() {
        let mut buf = Vec::new();
        write_message(&mut buf, &json!({ "type": "status" })).unwrap();
        assert_eq!(&buf[..4], &(buf.len() as u32 - 4).to_ne_bytes());
        let mut input = &buf[..];
        assert_eq!(read_message(&mut input).unwrap().unwrap(), json!({ "type": "status" }));
        assert!(read_message(&mut input).is_none());
    }

    #[test]
    fn oversized_messages_are_refused() {
        let mut input = &(MAX_MESSAGE + 1).to_ne_bytes()[..];
        assert!(read_message(&mut input).is_none());
    }

    #[test]
    fn requests_parse() {
        let r: Request = serde_json::from_value(json!({ "type": "code", "account": "a1", "parent_window": 42 })).unwrap();
        assert!(matches!(r, Request::Code { ref account, parent_window: 42 } if account == "a1"));
        let r: Request = serde_json::from_value(json!({ "type": "accounts", "parent_window": 0 })).unwrap();
        assert!(matches!(r, Request::Accounts { refresh: false }));
    }
}
