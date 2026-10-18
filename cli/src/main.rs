//! `totem`: get 2FA codes from your phone on the command line.
//!
//! ```text
//! totem pair --relay ws://192.168.0.102:8080/v1/ws   # once: shows a QR code to scan with the phone
//! totem list                                         # accounts on the phone
//! totem code github                                  # copies the GitHub code to the clipboard
//! ```

use clap::{Parser, Subcommand};
use std::time::Duration;
use totem_client::config::Config;
use totem_client::{describe, now, pair, pc_name, PairEvent, Session};

#[derive(Parser)]
#[command(name = "totem", about = "Get 2FA codes from your phone without touching it")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Pair with your phone (shows a QR code to scan in Totem → Settings → Pair with a PC).
    Pair {
        /// Relay WebSocket URL, e.g. ws://192.168.0.102:8080/v1/ws. Remembered after the first time.
        #[arg(long)]
        relay: Option<String>,
    },
    /// List the accounts on your phone.
    List,
    /// Get a code and copy it to the clipboard. QUERY matches the service or account name.
    Code {
        query: String,
        /// Only print the code; don't touch the clipboard.
        #[arg(long)]
        no_copy: bool,
        /// Don't wait to clear the clipboard afterwards.
        #[arg(long)]
        no_clear: bool,
    },
    /// Show this PC's id and paired phone.
    Status,
    /// Forget the paired phone (also remove this PC on the phone).
    Unpair { name: String },
}

const CLIPBOARD_CLEAR_AFTER: u64 = 20;

#[tokio::main]
async fn main() {
    let cli = Cli::parse();
    let result = match cli.command {
        Command::Pair { relay } => pair_cmd(relay).await,
        Command::List => list().await,
        Command::Code { query, no_copy, no_clear } => code(&query, no_copy, no_clear).await,
        Command::Status => status(),
        Command::Unpair { name } => unpair(&name),
    };
    if let Err(e) = result {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}

async fn pair_cmd(relay_arg: Option<String>) -> Result<(), String> {
    let mut config = Config::load_or_create()?;
    let relay = relay_arg
        .or_else(|| config.relay_url().map(str::to_string))
        .ok_or("first time: pass --relay ws://<relay host>:8080/v1/ws")?;
    let phone = pair(&mut config, &relay, |event| match event {
        PairEvent::ShowQr(uri) => {
            let code = qrcode::QrCode::new(uri.as_bytes()).expect("pairing link fits in a QR code");
            let art = code
                .render::<qrcode::render::unicode::Dense1x2>()
                .dark_color(qrcode::render::unicode::Dense1x2::Light)
                .light_color(qrcode::render::unicode::Dense1x2::Dark)
                .quiet_zone(true)
                .build();
            println!("{art}");
            println!("On your phone: Totem → Settings → Pair with a PC, and scan this code.");
            println!("(Valid for {} minutes.)\n", totem_client::PAIRING_VALID_FOR / 60);
            if std::env::var_os("TOTEM_SHOW_PAIRING_URI").is_some() {
                println!("{uri}\n");
            }
        }
        PairEvent::Confirm { phone_name, code } => {
            println!("Phone found: {phone_name}");
            println!("Check that your phone shows this code, then confirm on the phone:\n\n        {} {}\n", &code[..3], &code[3..]);
        }
    })
    .await?;
    println!("Paired with {}. Try: totem list", phone.name);
    Ok(())
}

async fn list() -> Result<(), String> {
    let mut config = Config::load_or_create()?;
    let mut session = Session::open(&config).await?;
    let accounts = session.accounts().await?;
    if accounts.is_empty() {
        println!("No accounts on {}.", session.phone.name);
    }
    for a in &accounts {
        let policy = match a.policy.as_str() {
            "auto" => "automatic",
            "ask" => "asks on phone",
            _ => "phone only",
        };
        println!("{:<40} {}", describe(a), policy);
    }
    config.set_account_cache(accounts);
    config.save()
}

async fn code(query: &str, no_copy: bool, no_clear: bool) -> Result<(), String> {
    let config = Config::load_or_create()?;
    let mut session = Session::open(&config).await?;
    let accounts = session.accounts().await?;
    let q = query.to_lowercase();
    let matches: Vec<_> = accounts.iter().filter(|a| describe(a).to_lowercase().contains(&q)).collect();
    let account = match matches.as_slice() {
        [] => return Err(format!("no account matches \"{query}\" (try `totem list`)")),
        [one] => *one,
        many => {
            // An exact, unique service-name match wins; otherwise ask for something more specific.
            let exact: Vec<_> = many.iter().filter(|a| a.issuer.eq_ignore_ascii_case(query)).collect();
            match exact.as_slice() {
                [one] => **one,
                _ => {
                    let names: Vec<_> = many.iter().map(|a| describe(a)).collect();
                    return Err(format!("\"{query}\" matches several accounts: {}", names.join(", ")));
                }
            }
        }
    };
    if account.policy == "ask" {
        println!("Approve on your phone…");
    }
    let code = session.code(&account.id).await.map_err(|e| format!("{}: {e}", describe(account)))?;
    let left = code.valid_until.saturating_sub(now());
    let c = &code.code;
    println!("{}: {} {}  (valid ~{left}s)", describe(account), &c[..c.len() / 2], &c[c.len() / 2..]);
    if no_copy {
        return Ok(());
    }
    let mut clipboard = arboard::Clipboard::new().map_err(|e| format!("clipboard: {e}"))?;
    clipboard.set_text(c.clone()).map_err(|e| format!("clipboard: {e}"))?;
    println!("Copied.");
    if !no_clear {
        println!("Clearing the clipboard in {CLIPBOARD_CLEAR_AFTER}s (Ctrl+C to keep it).");
        tokio::time::sleep(Duration::from_secs(CLIPBOARD_CLEAR_AFTER)).await;
        if clipboard.get_text().ok().as_deref() == Some(c.as_str()) {
            let _ = clipboard.clear();
        }
    }
    Ok(())
}

fn status() -> Result<(), String> {
    let config = Config::load_or_create()?;
    println!("This PC:  {} ({})", pc_name(), config.device_id());
    println!("Relay:    {}", config.relay_url().unwrap_or("(not set)"));
    println!("Settings: {}", Config::dir().join("config.json").display());
    match config.phones().first() {
        None => println!("Phone:    none (run `totem pair`)"),
        Some(p) => println!("Phone:    {} ({})", p.name, p.id),
    }
    Ok(())
}

fn unpair(name: &str) -> Result<(), String> {
    let mut config = Config::load_or_create()?;
    if config.remove_phone(name) == 0 {
        return Err(format!("no paired phone called \"{name}\""));
    }
    config.save()?;
    println!("Forgot {name}. Also remove this PC in Totem → Settings → Paired PCs on the phone.");
    Ok(())
}
