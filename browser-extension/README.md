# Totem browser extension

Fills 2FA codes from your phone into web pages, in Chrome, Edge and Brave. It asks Totem for
Windows for the code, which asks the phone, so the same Windows Hello check, Approve requests
and phone-only rules apply as with Ctrl+Shift+A. The extension never holds keys or secrets.

## Install

1. Install Totem for Windows **0.4.0 or later** and open it once. At every start it registers
   itself with Chrome, Edge and Brave as the extension's helper (native messaging host
   `tech.miyukivigil.totem`, under `HKCU\Software\<browser>\NativeMessagingHosts`).
2. Open `chrome://extensions` (or `edge://extensions`), turn on **Developer mode**, click
   **Load unpacked** and choose this `browser-extension` folder.
3. Restart the browser if it was open before step 1.

The `key` in `manifest.json` fixes the extension ID to `mlfmkiabdgpmmnmgojbamobimfgkoflf`, the only
ID Totem for Windows answers.

## Use

- **In a 2FA box:** a small Totem button appears at its right edge. Click it. If exactly one of
  your accounts matches the site, its code is filled in. Otherwise the toolbar popup opens so you
  can pick one.
- **Toolbar button** (or **Alt+Shift+T**): accounts for this site come first. Enter fills the
  code into the box you're in (or the page's 2FA box). On pages without one, the code is shown and
  copied.

Accounts match a site by its registered name: an account with issuer "GitHub" matches
`github.com` and `gist.github.com`, but not `github.com.evil.io` or `github-login.com`. Picking an
account that doesn't match the page shows a warning first. A few sites whose domain isn't their
name (`microsoftonline.com`, `steampowered.com`, …) are listed in `ALIASES` in `totem.js`.

If Totem for Windows isn't running, the extension starts it.

## How it fits together

```
page ─ content.js ─┐
                   ├─ chrome.runtime.sendNativeMessage ─ totem-desktop.exe (host mode)
popup.js ──────────┘        ─ \\.\pipe\totem-browser-<user> ─ Totem tray app ─ relay ─ phone
```

The browser starts `totem-desktop.exe chrome-extension://<id>/` for each message. In that mode the
exe only passes the message to the running tray app over a named pipe
([browser.rs](../desktop/src-tauri/src/browser.rs)).

Tests for the site matching: `node --test browser-extension/`.
