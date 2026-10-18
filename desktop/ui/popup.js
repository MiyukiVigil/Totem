// Search box: shows cached account names instantly, refreshes them from the phone in the
// background, and copies the chosen account's code on Enter.
const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const search = document.getElementById("search");
const list = document.getElementById("list");
const empty = document.getElementById("empty");
const status = document.getElementById("status");
const pairBtn = document.getElementById("pair-btn");

const HINT = "↑↓ choose · Enter copy · Esc close";
const POLICY = { auto: "auto", ask: "approve on phone", never: "phone only" };
let accounts = [];
let shown = [];
let selected = 0;
let busy = false;

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
      const badge = document.createElement("span");
      badge.className = "badge";
      badge.textContent = POLICY[a.policy] ?? a.policy;
      li.append(name, badge);
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
    if (!busy) setStatus(HINT);
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
  refresh();
}

async function copy() {
  const account = shown[selected];
  if (!account || busy) return;
  if (account.policy === "never") {
    setStatus(`${account.title} is set to phone-only.`, "error");
    return;
  }
  busy = true;
  setStatus(account.policy === "ask" ? `Approve on your phone…` : `Getting ${account.title}…`);
  try {
    const r = await invoke("copy_code", { accountId: account.id });
    setStatus(`Copied ${account.title} · valid ${r.seconds_left}s · clipboard clears in 20s`, "ok");
    setTimeout(() => invoke("hide_popup"), 700);
  } catch (e) {
    setStatus(String(e), "error");
  } finally {
    busy = false;
  }
}

search.addEventListener("input", () => { selected = 0; render(); });
document.addEventListener("keydown", (e) => {
  if (e.key === "ArrowDown") { selected = Math.min(selected + 1, shown.length - 1); render(); e.preventDefault(); }
  else if (e.key === "ArrowUp") { selected = Math.max(selected - 1, 0); render(); e.preventDefault(); }
  else if (e.key === "Enter") { copy(); e.preventDefault(); }
  else if (e.key === "Escape") { invoke("hide_popup"); }
});
pairBtn.addEventListener("click", () => { invoke("open_pairing"); invoke("hide_popup"); });

listen("popup-opened", open);
open();
