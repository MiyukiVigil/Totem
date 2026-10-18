//! Totem for Windows: lives in the tray; Ctrl+Shift+A opens a search box, Enter copies the code
//! from your phone. Optionally runs the relay too, so a home setup is just "open Totem".

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod hello;

use serde::Serialize;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use tauri::menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, RunEvent, State, WebviewUrl, WebviewWindowBuilder, WindowEvent};
use tauri_plugin_autostart::ManagerExt;
use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Modifiers, Shortcut, ShortcutState};
use totem_client::config::Config;
use totem_client::{describe, pair, AccountInfo, PairEvent, Session};

const RELAY_PORT: u16 = 8080;
const CLIPBOARD_CLEAR_AFTER: Duration = Duration::from_secs(20);
/// The phone drops an idle session after ~8 s; reuse ours only well within that.
const SESSION_REUSE: Duration = Duration::from_secs(5);

#[derive(Default)]
struct AppState {
    session: tokio::sync::Mutex<Option<(Session, Instant)>>,
    /// A request is in flight: keep the popup open even if it loses focus (e.g. while the user
    /// approves on the phone).
    busy: AtomicBool,
    relay_error: std::sync::Mutex<Option<String>>,
    relay_running: AtomicBool,
    hello: hello::Gate,
}

#[derive(Serialize, Clone)]
struct AccountView {
    id: String,
    title: String,
    subtitle: String,
    policy: String,
}

impl From<&AccountInfo> for AccountView {
    fn from(a: &AccountInfo) -> Self {
        let (title, subtitle) = if a.issuer.is_empty() { (a.label.clone(), String::new()) } else { (a.issuer.clone(), a.label.clone()) };
        Self { id: a.id.clone(), title, subtitle, policy: a.policy.clone() }
    }
}

#[derive(Serialize)]
struct Status {
    paired: bool,
    phone: Option<String>,
    relay_url: Option<String>,
    hosting_relay: bool,
    relay_error: Option<String>,
    suggested_relay_url: String,
}

#[tauri::command]
fn status(state: State<'_, AppState>) -> Result<Status, String> {
    let config = Config::load_or_create()?;
    Ok(Status {
        paired: !config.phones().is_empty(),
        phone: config.phones().first().map(|p| p.name.clone()),
        relay_url: config.relay_url().map(str::to_string),
        hosting_relay: config.host_relay(),
        relay_error: state.relay_error.lock().unwrap().clone(),
        suggested_relay_url: format!("ws://{}:{RELAY_PORT}/v1/ws", lan_ip()),
    })
}

#[tauri::command]
fn cached_accounts() -> Result<Vec<AccountView>, String> {
    Ok(Config::load_or_create()?.account_cache().iter().map(AccountView::from).collect())
}

/// Runs `f` with a session to the phone, reusing a very recent one.
async fn with_session<T>(
    state: &AppState,
    f: impl for<'a> FnOnce(&'a mut Session) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<T, String>> + Send + 'a>>,
) -> Result<T, String> {
    let mut slot = state.session.lock().await;
    let fresh = matches!(slot.as_ref(), Some((_, used)) if used.elapsed() < SESSION_REUSE);
    if !fresh {
        let config = Config::load_or_create()?;
        // Our own relay: talk to it over loopback, which survives the PC getting a new address.
        let session = match config.relay_url() {
            Some(url) if config.host_relay() && url.to_ascii_lowercase().starts_with("ws://") => {
                Session::open_via(&config, &format!("ws://127.0.0.1:{RELAY_PORT}/v1/ws")).await?
            }
            _ => Session::open(&config).await?,
        };
        *slot = Some((session, Instant::now()));
    }
    let (session, used) = slot.as_mut().unwrap();
    let result = f(session).await;
    match &result {
        Ok(_) => *used = Instant::now(),
        Err(_) => *slot = None,
    }
    result
}

#[tauri::command]
async fn refresh_accounts(state: State<'_, AppState>) -> Result<Vec<AccountView>, String> {
    let accounts = with_session(&state, |s| Box::pin(s.accounts())).await?;
    let mut config = Config::load_or_create()?;
    config.set_account_cache(accounts.clone());
    config.save()?;
    Ok(accounts.iter().map(AccountView::from).collect())
}

#[derive(Serialize)]
struct Copied {
    name: String,
    seconds_left: u64,
}

#[tauri::command]
async fn copy_code(app: AppHandle, state: State<'_, AppState>, account_id: String) -> Result<Copied, String> {
    state.busy.store(true, Ordering::SeqCst);
    let result = async {
        check_hello(&app, &state).await?;
        let id = account_id.clone();
        let code = with_session(&state, move |s| Box::pin(async move { s.code(&id).await })).await?;
        copy_to_clipboard(&code.code)?;
        let name = Config::load_or_create()?
            .account_cache()
            .iter()
            .find(|a| a.id == account_id)
            .map(describe)
            .unwrap_or_default();
        Ok(Copied { name, seconds_left: code.valid_until.saturating_sub(totem_client::now()) })
    }
    .await;
    state.busy.store(false, Ordering::SeqCst);
    result
}

/// Asks for Windows Hello unless it passed within the last few minutes (see [`hello::GRACE`]).
async fn check_hello(app: &AppHandle, state: &AppState) -> Result<(), String> {
    if state.hello.fresh() || !Config::load_or_create()?.require_hello() {
        return Ok(());
    }
    let popup = app.get_webview_window("popup");
    let hwnd = popup.as_ref().and_then(|w| w.hwnd().ok()).map_or(0, |h| h.0 as isize);
    let outcome = tauri::async_runtime::spawn_blocking(move || hello::verify(hwnd, "Unlock Totem to copy 2FA codes"))
        .await
        .map_err(|e| e.to_string())??;
    if let Some(w) = popup {
        let _ = w.set_focus();
    }
    match outcome {
        hello::Outcome::Verified => {
            state.hello.passed();
            Ok(())
        }
        hello::Outcome::Cancelled => Err("Windows Hello was cancelled".into()),
        // Nothing to check with: don't lock the user out. The tray menu explains.
        hello::Outcome::Unavailable => {
            tracing::warn!("Windows Hello isn't set up on this PC; copying without it");
            Ok(())
        }
    }
}

/// Copies without leaving traces: kept out of Win+V history, cloud clipboard and clipboard
/// monitors, and cleared after 20 s unless something else was copied since.
fn copy_to_clipboard(code: &str) -> Result<(), String> {
    let mut clipboard = arboard::Clipboard::new().map_err(|e| format!("clipboard: {e}"))?;
    #[cfg(windows)]
    {
        use arboard::SetExtWindows;
        clipboard
            .set()
            .exclude_from_history()
            .exclude_from_cloud()
            .exclude_from_monitoring()
            .text(code.to_string())
            .map_err(|e| format!("clipboard: {e}"))?;
    }
    #[cfg(not(windows))]
    clipboard.set_text(code.to_string()).map_err(|e| format!("clipboard: {e}"))?;

    let code = code.to_string();
    std::thread::spawn(move || {
        std::thread::sleep(CLIPBOARD_CLEAR_AFTER);
        if let Ok(mut cb) = arboard::Clipboard::new() {
            if cb.get_text().ok().as_deref() == Some(code.as_str()) {
                let _ = cb.clear();
            }
        }
    });
    Ok(())
}

#[tauri::command]
fn hide_popup(app: AppHandle) {
    if let Some(w) = app.get_webview_window("popup") {
        let _ = w.hide();
    }
}

#[tauri::command]
fn open_pairing(app: AppHandle) {
    show_pairing_window(&app);
}

#[derive(Serialize, Clone)]
struct PairQr {
    svg: String,
}

#[derive(Serialize, Clone)]
struct PairConfirm {
    phone: String,
    code: String,
}

#[tauri::command]
async fn start_pairing(app: AppHandle, state: State<'_, AppState>, relay_url: String) -> Result<(), String> {
    *state.session.lock().await = None;
    let relay_url = relay_url.trim().to_string();
    totem_protocol::relay::check_relay_url(&relay_url)?;
    // A home address (ws://) is this PC's own relay, so make sure it's running.
    let local = relay_url.to_ascii_lowercase().starts_with("ws://");
    if local {
        start_relay(&app);
    }
    let mut config = Config::load_or_create()?;
    let emitter = app.clone();
    let result = pair(&mut config, &relay_url, move |event| {
        let _ = match event {
            PairEvent::ShowQr(uri) => {
                // For testing with client/examples/fake_phone.rs instead of a camera.
                if std::env::var_os("TOTEM_SHOW_PAIRING_URI").is_some() {
                    eprintln!("pairing link: {uri}");
                }
                let svg = qrcode::QrCode::new(uri.as_bytes())
                    .map(|q| q.render::<qrcode::render::svg::Color>().min_dimensions(280, 280).build())
                    .unwrap_or_default();
                emitter.emit_to("pair", "pair-qr", PairQr { svg })
            }
            PairEvent::Confirm { phone_name, code } => {
                emitter.emit_to("pair", "pair-confirm", PairConfirm { phone: phone_name, code: format!("{} {}", &code[..3], &code[3..]) })
            }
        };
    })
    .await;
    match result {
        Ok(phone) => {
            // Remember who runs the relay: with a wss:// one (e.g. Cloudflare) this PC stops
            // opening port 8080 from the next start.
            let mut config = Config::load_or_create()?;
            config.set_host_relay(local);
            config.save()?;
            // Fill the search box's cache straight away.
            let _ = refresh_accounts(state).await;
            Ok(app.emit_to("pair", "pair-done", phone.name).map_err(|e| e.to_string())?)
        }
        Err(e) => Err(e),
    }
}

#[tauri::command]
async fn unpair(state: State<'_, AppState>) -> Result<(), String> {
    *state.session.lock().await = None;
    let mut config = Config::load_or_create()?;
    let ids: Vec<String> = config.phones().iter().map(|p| p.id.clone()).collect();
    for id in ids {
        config.remove_phone(&id);
    }
    config.save()
}

fn show_popup(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("popup") {
        let _ = w.center();
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
        let _ = app.emit_to("popup", "popup-opened", ());
    }
}

fn show_pairing_window(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("pair") {
        let _ = w.show();
        let _ = w.set_focus();
        return;
    }
    let _ = WebviewWindowBuilder::new(app, "pair", WebviewUrl::App("pair.html".into()))
        .title("Pair Totem with your phone")
        .inner_size(460.0, 680.0)
        .resizable(false)
        .center()
        .build();
}

/// This PC's address on the home network (no packets are sent; this only asks the OS which
/// interface it would use).
fn lan_ip() -> String {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| {
            s.connect("192.0.2.1:9")?;
            s.local_addr()
        })
        .map(|a| a.ip().to_string())
        .unwrap_or_else(|_| "127.0.0.1".into())
}

/// Announces the relay on the home network (mDNS `_totem._tcp`, TXT `id` = this PC's relay id), so
/// the phone can find it again after the PC gets a new address. Best effort.
fn announce_relay() {
    static DAEMON: std::sync::OnceLock<mdns_sd::ServiceDaemon> = std::sync::OnceLock::new();
    let Ok(config) = Config::load_or_create() else { return };
    let host = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "totem-pc".into()).to_ascii_lowercase();
    let result = mdns_sd::ServiceDaemon::new().and_then(|daemon| {
        let info = mdns_sd::ServiceInfo::new(
            "_totem._tcp.local.",
            &format!("Totem {host}"),
            &format!("{host}.local."),
            "",
            RELAY_PORT,
            &[("id", config.device_id().as_str()), ("path", "/v1/ws")][..],
        )?
        .enable_addr_auto();
        daemon.register(info)?;
        Ok(daemon)
    });
    match result {
        Ok(daemon) => {
            let _ = DAEMON.set(daemon);
        }
        Err(e) => tracing::warn!("couldn't announce the relay on the network: {e}"),
    }
}

/// Firebase service-account key for waking the phone: `TOTEM_FCM_KEY`, or the first
/// `*firebase-adminsdk*.json` in `~/.secrets`.
fn fcm_key_path() -> Option<PathBuf> {
    if let Some(p) = std::env::var_os("TOTEM_FCM_KEY") {
        return Some(PathBuf::from(p));
    }
    let dir = dirs::home_dir()?.join(".secrets");
    std::fs::read_dir(dir).ok()?.filter_map(Result::ok).map(|e| e.path()).find(|p| {
        p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.contains("firebase-adminsdk") && n.ends_with(".json"))
    })
}

fn start_relay(app: &AppHandle) {
    if app.state::<AppState>().relay_running.swap(true, Ordering::SeqCst) {
        return;
    }
    let fcm = match fcm_key_path().map(|p| totem_relay::fcm::Fcm::from_key_file(&p)) {
        Some(Ok(f)) => Some(f),
        Some(Err(e)) => {
            *app.state::<AppState>().relay_error.lock().unwrap() = Some(format!("push key unusable: {e}"));
            None
        }
        None => {
            *app.state::<AppState>().relay_error.lock().unwrap() =
                Some("no Firebase key in ~/.secrets: the phone can't be woken, only reached while Totem is open on it".into());
            None
        }
    };
    let store = Config::dir().join("relay-store.json");
    let relay = totem_relay::Relay::new(Some(store), fcm);
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        match tokio::net::TcpListener::bind(("0.0.0.0", RELAY_PORT)).await {
            Ok(listener) => {
                announce_relay();
                if let Err(e) = axum::serve(listener, relay.router()).await {
                    *app.state::<AppState>().relay_error.lock().unwrap() = Some(format!("relay stopped: {e}"));
                }
            }
            Err(e) => {
                *app.state::<AppState>().relay_error.lock().unwrap() = Some(format!("can't listen on port {RELAY_PORT}: {e}"));
            }
        }
    });
}

fn main() {
    let app = tauri::Builder::default()
        // A second launch just opens the search box of the running one.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_popup(app)))
        .plugin(tauri_plugin_autostart::init(tauri_plugin_autostart::MacosLauncher::LaunchAgent, Some(vec!["--background"])))
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, _shortcut, event| {
                    if event.state() == ShortcutState::Pressed {
                        show_popup(app);
                    }
                })
                .build(),
        )
        .manage(AppState::default())
        .setup(|app| {
            let handle = app.handle().clone();
            if let Err(e) = app.global_shortcut().register(Shortcut::new(Some(Modifiers::CONTROL | Modifiers::SHIFT), Code::KeyA)) {
                tracing::warn!("Ctrl+Shift+A is taken by another app: {e}");
            }
            if Config::load_or_create().map(|c| c.host_relay()).unwrap_or(true) {
                start_relay(&handle);
            }

            let require_hello = Config::load_or_create().map(|c| c.require_hello()).unwrap_or(true);
            let autostart = app.autolaunch().is_enabled().unwrap_or(false);
            let menu = Menu::with_items(
                app,
                &[
                    &MenuItem::with_id(app, "code", "Get a code   Ctrl+Shift+A", true, None::<&str>)?,
                    &MenuItem::with_id(app, "pair", "Pair with phone…", true, None::<&str>)?,
                    &PredefinedMenuItem::separator(app)?,
                    &CheckMenuItem::with_id(app, "hello", "Ask for Windows Hello (every 10 min)", true, require_hello, None::<&str>)?,
                    &CheckMenuItem::with_id(app, "autostart", "Start with Windows", true, autostart, None::<&str>)?,
                    &PredefinedMenuItem::separator(app)?,
                    &MenuItem::with_id(app, "quit", "Quit Totem", true, None::<&str>)?,
                ],
            )?;
            TrayIconBuilder::with_id("main")
                .icon(app.default_window_icon().cloned().expect("app icon"))
                .tooltip("Totem: Ctrl+Shift+A for a 2FA code")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "code" => show_popup(app),
                    "pair" => show_pairing_window(app),
                    "hello" => {
                        if let Ok(mut config) = Config::load_or_create() {
                            let on = !config.require_hello();
                            config.set_require_hello(on);
                            let _ = config.save();
                            app.state::<AppState>().hello.forget();
                        }
                    }
                    "autostart" => {
                        let launch = app.autolaunch();
                        let _ = if launch.is_enabled().unwrap_or(false) { launch.disable() } else { launch.enable() };
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                        show_popup(tray.app_handle());
                    }
                })
                .build(app)?;

            // First run: nothing paired yet, so open the pairing window.
            if Config::load_or_create().map(|c| c.phones().is_empty()).unwrap_or(false) {
                show_pairing_window(&handle);
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if window.label() != "popup" {
                return;
            }
            match event {
                WindowEvent::Focused(false) => {
                    if !window.state::<AppState>().busy.load(Ordering::SeqCst) {
                        let _ = window.hide();
                    }
                }
                WindowEvent::CloseRequested { api, .. } => {
                    api.prevent_close();
                    let _ = window.hide();
                }
                _ => {}
            }
        })
        .invoke_handler(tauri::generate_handler![
            status,
            cached_accounts,
            refresh_accounts,
            copy_code,
            hide_popup,
            open_pairing,
            start_pairing,
            unpair
        ])
        .build(tauri::generate_context!())
        .expect("error while building Totem");

    app.run(|_app, event| {
        // Keep running in the tray when the last window closes; only "Quit" exits.
        if let RunEvent::ExitRequested { api, code: None, .. } = event {
            api.prevent_exit();
        }
    });
}
