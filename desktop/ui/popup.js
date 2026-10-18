// Search box: shows cached account names instantly, refreshes them from the phone in the
// background, and copies the chosen account's code on Enter.
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const search = document.getElementById("search");
const list = document.getElementById("list");
const empty = document.getElementById("empty");
const status = document.getElementById("status");
const pairBtn = document.getElementById("pair-btn");

const HINT = "↑↓ choose · Enter copy · Shift+Enter type it · Esc close";
const POLICY = { auto: "auto", ask: "approve on phone", never: "phone only" };
let accounts = [];
let shown = [];
let selected = 0;
let busy = false;
/** A message from a tray action stays until the next keypress. */
let sticky = false;

// Brand logos (Simple Icons, bundled; see scripts/brand-icons.mjs). Loaded once, in the background.
let brands = new Map();
fetch("brand-icons.json")
  .then((r) => r.json())
  .then((d) => { brands = new Map(d.icons.map((i) => [i.s, i])); render(); })
  .catch(() => {});

// Same letter colors as the phone (android/core BrandIcons.kt, LetterBadge).
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
  const brand = a.icon ? brands.get(a.icon) : null;
  const hex = brand ? brand.h : letterColor(a.title);
  const el = document.createElement("span");
  el.className = "logo";
  el.style.background = "#" + hex;
  el.style.color = isLight(hex) ? "#000" : "#fff";
  if (brand) {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    const path = document.createElementNS(ns, "path");
    path.setAttribute("d", brand.p);
    path.setAttribute("fill", "currentColor");
    svg.append(path);
    el.append(svg);
    el.title = brand.t;
  } else {
    el.textContent = ([...a.title].find((c) => /[\p{L}\p{N}]/u.test(c)) ?? "?").toUpperCase();
  }
  return el;
}

function setStatus(text, cls = "muted") {
  status.textContent = text;
  status.className = cls;
}

function render() {
  const q = search.value.trim().toLowerCase();
  shown = accounts.filter((a) => (a.title + " " + a.subtitle).toLowerCase().includes(q));
  // Accounts set to "phone only" can't be fetched, so they go last.
  shown.sort((a, b) => (a.policy === "never") - (b.policy === "never"));
  selected = Math.min(selected, Math.max(shown.length - 1, 0));
  list.replaceChildren(
    ...shown.map((a, i) => {
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
      const policyTag = document.createElement("span");
      policyTag.className = "badge";
      policyTag.textContent = POLICY[a.policy] ?? a.policy;
      li.append(badge(a), name, policyTag);
      li.addEventListener("click", () => { selected = i; copy(); });
      return li;
    }),
  );
  empty.classList.toggle("hidden", shown.length > 0 || accounts.length === 0);
  if (shown.length === 0 && accounts.length > 0) empty.textContent = "No account matches.";
  list.children[selected]?.scrollIntoView({ block: "nearest" });
}

async function refresh() {
  try {
    accounts = await invoke("refresh_accounts");
    render();
    if (!busy && !sticky) setStatus(HINT);
  } catch (e) {
    if (!busy) setStatus(accounts.length ? `Couldn't refresh: ${e}` : String(e), "error");
  }
}

async function open() {
  search.value = "";
  selected = 0;
  search.focus();
  const s = await invoke("status");
  pairBtn.classList.toggle("hidden", s.paired);
  if (!s.paired) {
    accounts = [];
    render();
    empty.classList.remove("hidden");
    empty.textContent = "No phone paired yet.";
    setStatus("Pair your phone to get started.");
    return;
  }
  accounts = await invoke("cached_accounts");
  render();
  setStatus(accounts.length ? HINT : `Asking ${s.phone}…`);
  sticky = Boolean(s.notice);
  if (s.notice) setStatus(s.notice, s.notice.startsWith("Couldn't") ? "error" : "ok");
  refresh();
}

async function copy(typeIt = false) {
  const account = shown[selected];
  if (!account || busy) return;
  if (account.policy === "never") {
    setStatus(`${account.title} is set to phone-only.`, "error");
    return;
  }
  busy = true;
  setStatus(account.policy === "ask" ? `Approve on your phone…` : `Getting ${account.title}…`);
  // The phone's daily check-in can turn the first "auto" code of the day into an approval.
  const slow = setTimeout(() => { if (busy) setStatus("Waiting… (Windows Hello, or Approve on your phone for the first code today)"); }, 2500);
  try {
    const r = await invoke("copy_code", { accountId: account.id, typeIt });
    if (typeIt) return; // already typed into the window underneath; the popup is hidden
    setStatus(`Copied ${account.title} · valid ${r.seconds_left}s · clipboard clears in 20s`, "ok");
    setTimeout(() => invoke("hide_popup"), 700);
  } catch (e) {
    setStatus(String(e), "error");
  } finally {
    clearTimeout(slow);
    busy = false;
  }
}

search.addEventListener("input", () => { selected = 0; render(); });
document.addEventListener("keydown", (e) => {
  sticky = false;
  if (e.key === "ArrowDown") { selected = Math.min(selected + 1, shown.length - 1); render(); e.preventDefault(); }
  else if (e.key === "ArrowUp") { selected = Math.max(selected - 1, 0); render(); e.preventDefault(); }
  else if (e.key === "Enter") { copy(e.shiftKey); e.preventDefault(); }
  else if (e.key === "Escape") { invoke("hide_popup"); }
});
pairBtn.addEventListener("click", () => { invoke("open_pairing"); invoke("hide_popup"); });

listen("popup-opened", open);
open();
