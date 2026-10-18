use clap::Parser;
use std::path::PathBuf;

/// Totem relay: forwards end-to-end encrypted messages between paired devices.
#[derive(Parser)]
struct Args {
    /// Address to listen on.
    #[arg(long, default_value = "0.0.0.0:8080")]
    listen: String,
    /// Where to keep push tokens and allow lists.
    #[arg(long, default_value = "totem-relay-store.json")]
    store: PathBuf,
    /// Firebase service-account key, for waking phones. Without it, offline phones aren't woken.
    #[arg(long, env = "TOTEM_FCM_KEY")]
    fcm_key: Option<PathBuf>,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();
    let args = Args::parse();
    let fcm = args.fcm_key.as_deref().map(|p| totem_relay::fcm::Fcm::from_key_file(p).unwrap_or_else(|e| panic!("{e}")));
    if fcm.is_none() {
        tracing::warn!("no --fcm-key: offline phones will not be woken");
    }
    let relay = totem_relay::Relay::new(Some(args.store), fcm);
    let listener = tokio::net::TcpListener::bind(&args.listen).await.expect("bind");
    tracing::info!("listening on {} (ws path /v1/ws)", args.listen);
    axum::serve(listener, relay.router()).await.unwrap();
}
