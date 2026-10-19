// Shared by the popup and the background worker: talking to Totem for Windows, and deciding
// which accounts belong to a site.

/** Native messaging host registered by the desktop app (desktop/src-tauri/src/browser.rs). */
export const HOST = "tech.miyukivigil.totem";

/** Sends one request to Totem for Windows; resolves with its answer or throws its error. */
export async function ask(request) {
  let reply;
  try {
    reply = await chrome.runtime.sendNativeMessage(HOST, request);
  } catch (e) {
    throw new Error(/not found|forbidden/i.test(e.message)
      ? "Totem for Windows isn't set up for the browser yet: install or update it, open it once, then restart the browser."
      : `Totem: ${e.message}`);
  }
  if (!reply?.ok) throw new Error(reply?.error ?? "no answer from Totem");
  return reply;
}

// Country-code domains that register names one level down (example.co.uk, example.com.au, …).
const SECOND_LEVEL = new Set(["co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go"]);

/**
 * The name a site is registered under: "github" for gist.github.com, "bbc" for www.bbc.co.uk.
 * Only this part is compared with account names, so github.com.evil.io gives "evil".
 */
export function siteName(hostname) {
  const labels = hostname.toLowerCase().replace(/\.$/, "").split(".").filter(Boolean);
  if (labels.length < 2) return labels[0] ?? "";
  let i = labels.length - 2;
  if (labels.length >= 3 && SECOND_LEVEL.has(labels[i]) && labels[labels.length - 1].length === 2) i--;
  return labels[i];
}

// Sites whose domain isn't their name: domain name → names an account might use.
const ALIASES = {
  microsoftonline: ["microsoft"], live: ["microsoft"], outlook: ["microsoft"], office: ["microsoft"], xbox: ["microsoft"],
  steampowered: ["steam"], steamcommunity: ["steam"],
  aws: ["amazonwebservices", "amazon"],
  youtube: ["google"], gmail: ["google"],
  x: ["twitter"], twitter: ["x"],
  proton: ["protonmail"], protonmail: ["proton"],
  npmjs: ["npm"],
  epicgames: ["epic"],
  battle: ["battlenet", "blizzard"], blizzard: ["battlenet"],
};

const normalize = (s) => (s ?? "").toLowerCase().replace(/&/g, "and").replace(/[^\p{L}\p{N}]/gu, "");

function accountKeys(account) {
  const keys = new Set();
  const add = (s, min = 1) => { const n = normalize(s); if (n.length >= min) keys.add(n); };
  add(account.issuer);
  add(account.icon);
  // "Amazon Web Services" → "amazon"; "example.com" → "example".
  add((account.issuer ?? "").split(/[\s.:/(),_-]+/).find(Boolean), 3);
  return keys;
}

/** True when `account` looks like it belongs to the site at `hostname`. */
export function matchesSite(account, hostname) {
  if (!hostname) return false;
  const name = normalize(siteName(hostname));
  if (!name) return false;
  const keys = accountKeys(account);
  return keys.has(name) || (ALIASES[name] ?? []).some((k) => keys.has(k));
}

/** The hostname of an http(s) page, or "" for anything else. */
export function hostOf(url) {
  try {
    const u = new URL(url);
    return u.protocol === "https:" || u.protocol === "http:" ? u.hostname : "";
  } catch {
    return "";
  }
}
