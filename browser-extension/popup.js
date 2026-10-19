// Toolbar popup: lists the phone's accounts (this site's first), and fills the chosen one's code
// into the page's code box, or copies it when the page has none.
import { ask, hostOf, matchesSite } from "./totem.js";

const search = document.getElementById("search");
const list = document.getElementById("list");
const empty = document.getElementById("empty");
const status = document.getElementById("status");
const shownCode = document.getElementById("shown-code");

const HINT = "↑↓ choose · Enter fill";
const POLICY = { auto: "auto", ask: "approve on phone", never: "phone only" };
let tab = null;
let host = "";
let accounts = [];
let shown = [];
let selected = 0;
let busy = false;
/** Account the user was warned about (doesn't match this site); a second Enter fills anyway. */
let warnedFor = null;

// Letter badges: same colors as the phone and the desktop app (desktop/ui/popup.js).
const BRAND_COLORS = { microsoft: "0078D4", outlook: "0078D4", xbox: "107C10", azure: "0078D4", linkedin: "0A66C2",
  amazon: "FF9900", aws: "232F3E", amazonwebservices: "232F3E", openai: "10A37F", chatgpt: "10A37F", adobe: "FF0000",
  slack: "4A154B", yahoo: "6001D2", heroku: "430098", nintendo: "E60012", minecraft: "62B47A", kraken: "5741D9",
  tuta: "850122", tutanota: "850122", fastmail: "0067B9" };
const PALETTE = ["B5652B", "2B7BB5", "2E9E6B", "8E44AD", "C0392B", "D35400", "16A085", "7F8C8D", "2C3E50", "B03A75"];
const normalize = (s) => s.toLowerCase().replace(/&/g, "and").replace(/\+/g, "plus").replace(/[^\p{L}\p{N}]/gu, "");
function javaHash(s) {
  let h = 0;
  for (const ch of s) for (let k = 0; k < ch.length; k++) h = (Math.imul(31, h) + ch.charCodeAt(k)) | 0;
  return h;
}
function letterColor(name) {
  const n = normalize(name);
  for (const [brand, hex] of Object.entries(BRAND_COLORS)) if (n === brand || n.startsWith(brand)) return hex;
  return PALETTE[((javaHash(n) % PALETTE.length) + PALETTE.length) % PALETTE.length];
}
function isLight(hex) {
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.6;
}
function badge(a) {
  const hex = a.logo ? a.logo.h : letterColor(a.title);
  const el = document.createElement("span");
  el.className = "logo";
  el.style.background = "#" + hex;
  el.style.color = isLight(hex) ? "#000" : "#fff";
  if (a.logo) {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    const path = document.createElementNS(ns, "path");
    path.setAttribute("d", a.logo.p);
    path.setAttribute("fill", "currentColor");
    svg.append(path);
    el.append(svg);
    el.title = a.logo.t;
  } else {
    el.textContent = ([...a.title].find((c) => /[\p{L}\p{N}]/u.test(c)) ?? "?").toUpperCase();
  }
  return el;
}

function setStatus(text, cls = "muted") {
  status.textContent = text;
  status.className = cls;
}

function row(a, i) {
  const li = document.createElement("li");
  li.setAttribute("role", "option");
  li.className = i === selected ? "selected" : "";
  const name = document.createElement("div");
  name.className = "name";
  const title = document.createElement("div");
  title.className = "title";
  title.textContent = a.title;
  const sub = document.createElement("div");
  sub.className = "sub";
  sub.textContent = a.subtitle;
  name.append(title, sub);
  const tag = document.createElement("span");
  tag.className = "badge";
  tag.textContent = POLICY[a.policy] ?? a.policy;
  li.append(badge(a), name, tag);
  li.addEventListener("click", () => { selected = i; choose(); });
  return li;
}

function divider(text) {
  const li = document.createElement("li");
  li.className = "divider";
  li.textContent = text;
  return li;
}

function render() {
  const q = search.value.trim().toLowerCase();
  const rank = (a) => (matchesSite(a, host) ? 0 : 1) + (a.policy === "never" ? 2 : 0);
  shown = accounts.filter((a) => (a.title + " " + a.subtitle).toLowerCase().includes(q)).sort((a, b) => rank(a) - rank(b));
  selected = Math.min(selected, Math.max(shown.length - 1, 0));
  const items = [];
  shown.forEach((a, i) => {
    const here = matchesSite(a, host);
    if (i === 0 && here) items.push(divider(`For ${host}`));
    if (i > 0 && !here && matchesSite(shown[i - 1], host)) items.push(divider("Other accounts"));
    items.push(row(a, i));
  });
  list.replaceChildren(...items);
  empty.classList.toggle("hidden", shown.length > 0 || accounts.length === 0);
  if (shown.length === 0 && accounts.length > 0) empty.textContent = "No account matches.";
  list.querySelector("li.selected")?.scrollIntoView({ block: "nearest" });
}

/** Finds the frame to fill: the one with a focused text box, else one with a code box. */
async function findFrame() {
  if (!tab || !host) return null;
  const probe = () => chrome.scripting.executeScript({ target: { tabId: tab.id, allFrames: true }, func: () => globalThis.totemProbe?.() ?? null });
  try {
    let results = await probe();
    // Tabs opened before the extension was installed don't have content.js yet.
    if (results.every((r) => !r.result)) {
      await chrome.scripting.executeScript({ target: { tabId: tab.id, allFrames: true }, files: ["content.js"] });
      results = await probe();
    }
    const usable = results.filter((r) => r.result);
    return usable.find((r) => r.result.focused && r.result.code) ?? usable.find((r) => r.result.code) ?? usable.find((r) => r.result.focused) ?? null;
  } catch {
    return null; // pages the extension may not touch (store, settings, …)
  }
}

async function choose() {
  const account = shown[selected];
  if (!account || busy) return;
  if (account.policy === "never") return setStatus(`${account.title} is set to phone-only.`, "error");
  busy = true;
  try {
    const frame = await findFrame();
    const site = frame?.result.host ?? host;
    // Guard against look-alike sites: filling a code for an account that isn't this site's
    // needs a second press.
    if (site && !matchesSite(account, site) && warnedFor !== account.id) {
      warnedFor = account.id;
      return setStatus(`This page is ${site}, which doesn't look like ${account.title}. Press again to fill anyway.`, "error");
    }
    setStatus(account.policy === "ask" ? "Approve on your phone…" : `Getting ${account.title}…`);
    const slow = setTimeout(() => setStatus("Waiting… (Windows Hello, or Approve on your phone for the first code today)"), 2500);
    let reply;
    try {
      reply = await ask({ type: "code", account: account.id });
    } finally {
      clearTimeout(slow);
    }
    if (frame) {
      const [filled] = await chrome.scripting.executeScript({
        target: { tabId: tab.id, frameIds: [frame.frameId] },
        func: (code) => globalThis.totemFill?.(code) ?? false,
        args: [reply.code],
      });
      if (filled?.result) {
        setStatus(`Filled ${account.title} · valid ${reply.seconds_left}s`, "ok");
        setTimeout(() => window.close(), 700);
        return;
      }
    }
    await navigator.clipboard.writeText(reply.code);
    shownCode.textContent = reply.code.replace(/^(\d{3})(\d{3})$/, "$1 $2");
    shownCode.classList.remove("hidden");
    setStatus(`No code box on this page, so it's copied · valid ${reply.seconds_left}s`, "ok");
  } catch (e) {
    setStatus(e.message, "error");
  } finally {
    busy = false;
  }
}

async function open() {
  [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  host = hostOf(tab?.url ?? "");
  try {
    const s = await ask({ type: "status" });
    if (!s.paired) {
      empty.textContent = "No phone paired yet. Pair one in Totem on this PC (tray icon → Pair with phone).";
      empty.classList.remove("hidden");
      return setStatus("");
    }
    accounts = (await ask({ type: "accounts" })).accounts;
    render();
    setStatus(accounts.length ? HINT : `Asking ${s.phone}…`);
    accounts = (await ask({ type: "accounts", refresh: true })).accounts;
    render();
    if (!busy) setStatus(HINT);
  } catch (e) {
    if (!busy) setStatus(accounts.length ? `Couldn't refresh: ${e.message}` : e.message, "error");
  }
}

search.addEventListener("input", () => { selected = 0; warnedFor = null; render(); });
document.addEventListener("keydown", (e) => {
  if (e.key === "ArrowDown") { selected = Math.min(selected + 1, shown.length - 1); render(); e.preventDefault(); }
  else if (e.key === "ArrowUp") { selected = Math.max(selected - 1, 0); render(); e.preventDefault(); }
  else if (e.key === "Enter") { choose(); e.preventDefault(); }
});

open();
