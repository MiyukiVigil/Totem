//! A 32-byte key that only Windows Hello can produce, for encrypting this PC's private keys.
//!
//! Windows Hello "key credentials" are RSA keys kept by Windows (in the TPM where there is one),
//! and using one always asks for face / fingerprint / PIN. Their signatures are RSASSA-PKCS1-v1_5,
//! which is deterministic: signing the same challenge always gives the same bytes. SHA-256 of that
//! signature is the key. It's never stored; it's recreated on each unlock.

use sha2::{Digest, Sha256};

const CREDENTIAL_NAME: &str = "Totem PC keys";
const CHALLENGE: &[u8] = b"totem-pc-keys-v1: unlock this PC's Totem keys";

/// `create = true` makes a new Windows Hello credential (replacing any old one); otherwise the
/// existing one is used. Blocks until the user answers the Windows Hello prompt.
#[cfg(windows)]
pub fn derive(create: bool) -> Result<[u8; 32], String> {
    use windows::core::HSTRING;
    use windows::Security::Credentials::{KeyCredentialCreationOption, KeyCredentialManager, KeyCredentialStatus};
    use windows::Security::Cryptography::CryptographicBuffer;

    let e = |e: windows::core::Error| format!("Windows Hello: {e}");
    if !KeyCredentialManager::IsSupportedAsync().and_then(|op| op.join()).map_err(e)? {
        return Err("Windows Hello isn't set up on this PC (Settings → Accounts → Sign-in options)".into());
    }
    let _focus = FocusHelloPrompt::start();
    let name = HSTRING::from(CREDENTIAL_NAME);
    let result = if create {
        KeyCredentialManager::RequestCreateAsync(&name, KeyCredentialCreationOption::ReplaceExisting)
    } else {
        KeyCredentialManager::OpenAsync(&name)
    }
    .and_then(|op| op.join())
    .map_err(e)?;
    match result.Status().map_err(e)? {
        KeyCredentialStatus::Success => {}
        KeyCredentialStatus::NotFound => return Err(MISSING.into()),
        KeyCredentialStatus::UserCanceled => return Err("Windows Hello was cancelled".into()),
        other => return Err(format!("Windows Hello said no ({other:?})")),
    }
    let credential = result.Credential().map_err(e)?;
    let challenge = CryptographicBuffer::CreateFromByteArray(CHALLENGE).map_err(e)?;
    let signed = credential.RequestSignAsync(&challenge).and_then(|op| op.join()).map_err(e)?;
    match signed.Status().map_err(e)? {
        KeyCredentialStatus::Success => {}
        KeyCredentialStatus::UserCanceled => return Err("Windows Hello was cancelled".into()),
        other => return Err(format!("Windows Hello said no ({other:?})")),
    }
    let mut signature = windows_core::Array::<u8>::new();
    CryptographicBuffer::CopyToByteArray(&signed.Result().map_err(e)?, &mut signature).map_err(e)?;
    let key: [u8; 32] = Sha256::new_with_prefix(b"totem-pc-keys-v1").chain_update(&*signature).finalize().into();
    Ok(key)
}

#[cfg(not(windows))]
pub fn derive(_create: bool) -> Result<[u8; 32], String> {
    Err("Windows Hello is only available on Windows".into())
}

/// Error text when the Windows Hello credential is gone (e.g. Windows Hello was reset).
pub const MISSING: &str = "the Windows Hello key for Totem is gone (was Windows Hello reset?)";

/// Brings the Windows Hello dialog to the front while it's open: started from a tray app it can
/// otherwise open behind other windows.
#[cfg(windows)]
struct FocusHelloPrompt(std::sync::Arc<std::sync::atomic::AtomicBool>);

#[cfg(windows)]
impl FocusHelloPrompt {
    fn start() -> Self {
        use std::sync::atomic::Ordering;
        let done = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let flag = done.clone();
        std::thread::spawn(move || {
            use windows::core::w;
            use windows::Win32::UI::WindowsAndMessaging::{FindWindowW, SetForegroundWindow};
            for _ in 0..50 {
                if flag.load(Ordering::SeqCst) {
                    return;
                }
                if let Ok(hwnd) = unsafe { FindWindowW(w!("Credential Dialog Xaml Host"), None) } {
                    if !hwnd.is_invalid() {
                        let _ = unsafe { SetForegroundWindow(hwnd) };
                        return;
                    }
                }
                std::thread::sleep(std::time::Duration::from_millis(100));
            }
        });
        Self(done)
    }
}

#[cfg(windows)]
impl Drop for FocusHelloPrompt {
    fn drop(&mut self) {
        self.0.store(true, std::sync::atomic::Ordering::SeqCst);
    }
}
