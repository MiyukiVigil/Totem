//! Windows Hello (face, fingerprint or PIN) before a code is copied, so someone at an unlocked
//! PC can't just press Ctrl+Shift+A. One check covers [`GRACE`].

use std::sync::Mutex;
use std::time::{Duration, Instant};

/// After a successful check, codes copy without asking again for this long.
pub const GRACE: Duration = Duration::from_secs(10 * 60);

pub enum Outcome {
    Verified,
    Cancelled,
    /// No Windows Hello on this PC (no PIN set up, or disabled by policy).
    Unavailable,
}

#[derive(Default)]
pub struct Gate {
    last_ok: Mutex<Option<Instant>>,
}

impl Gate {
    pub fn fresh(&self) -> bool {
        self.last_ok.lock().unwrap().is_some_and(|t| t.elapsed() < GRACE)
    }

    pub fn passed(&self) {
        *self.last_ok.lock().unwrap() = Some(Instant::now());
    }

    pub fn forget(&self) {
        *self.last_ok.lock().unwrap() = None;
    }
}

/// Shows the Windows Hello prompt on top of `hwnd`. Blocks until the user answers.
#[cfg(windows)]
pub fn verify(hwnd: isize, message: &str) -> Result<Outcome, String> {
    use windows::core::{factory, HSTRING};
    use windows::Security::Credentials::UI::{
        UserConsentVerificationResult, UserConsentVerifier, UserConsentVerifierAvailability,
    };
    use windows::Win32::Foundation::HWND;
    use windows::Win32::System::WinRT::IUserConsentVerifierInterop;

    let available = UserConsentVerifier::CheckAvailabilityAsync()
        .and_then(|op| op.join())
        .map_err(|e| format!("Windows Hello: {e}"))?;
    if available != UserConsentVerifierAvailability::Available {
        return Ok(Outcome::Unavailable);
    }
    let interop = factory::<UserConsentVerifier, IUserConsentVerifierInterop>().map_err(|e| format!("Windows Hello: {e}"))?;
    let op: windows_future::IAsyncOperation<UserConsentVerificationResult> =
        unsafe { interop.RequestVerificationForWindowAsync(HWND(hwnd as _), &HSTRING::from(message)) }
            .map_err(|e| format!("Windows Hello: {e}"))?;
    match op.join().map_err(|e| format!("Windows Hello: {e}"))? {
        UserConsentVerificationResult::Verified => Ok(Outcome::Verified),
        UserConsentVerificationResult::DeviceNotPresent
        | UserConsentVerificationResult::NotConfiguredForUser
        | UserConsentVerificationResult::DisabledByPolicy => Ok(Outcome::Unavailable),
        _ => Ok(Outcome::Cancelled),
    }
}

#[cfg(not(windows))]
pub fn verify(_hwnd: isize, _message: &str) -> Result<Outcome, String> {
    Ok(Outcome::Unavailable)
}
