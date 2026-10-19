// Runs in every page: spots 2FA code boxes, shows a small Totem button in the one you're typing
// in, and fills codes (for the button, and for the popup through totemFill / totemProbe).
(() => {
  if (globalThis.totemFill) return;

  const HINT = /(^|[^a-z])t?otp|t?otp($|[^a-z])|2fa|mfa|one.?time|two.?(factor|step)|(verification|security|auth\w*|verify|login|sign.?in|access|sms|email)[-_ ]?code|passcode|approvals_code|token|^code$|^pin$/i;
  const NOT = /zip|postal|phone|promo|coupon|captcha|discount|gift|voucher|card|cvc|cvv|search|country|referral|invite/i;
  const TEXT_TYPES = new Set(["text", "tel", "number", "password", ""]);

  const isTextField = (el) => el instanceof HTMLInputElement && TEXT_TYPES.has(el.type) && !el.disabled && !el.readOnly;
  const visible = (el) => el.getClientRects().length > 0 && getComputedStyle(el).visibility !== "hidden";

  /** The focused element, looking inside open shadow roots. */
  function deepActive() {
    let el = document.activeElement;
    while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
    return el;
  }

  /** Inputs of a "one box per digit" code (4–8 boxes of one character each), or null. */
  function boxGroup(el) {
    if (!isTextField(el) || el.maxLength !== 1) return null;
    let scope = el.parentElement;
    for (let up = 0; scope && up < 4; up++, scope = scope.parentElement) {
      const boxes = [...scope.querySelectorAll("input")].filter((i) => isTextField(i) && i.maxLength === 1 && visible(i));
      if (boxes.length >= 4) return boxes.length <= 8 ? boxes : null;
    }
    return null;
  }

  function isCodeField(el) {
    if (!isTextField(el) || !visible(el)) return false;
    if (el.autocomplete === "one-time-code") return true;
    if (boxGroup(el)) return true;
    const hints = [el.name, el.id, el.placeholder, el.getAttribute("aria-label"), el.autocomplete, el.labels?.[0]?.textContent]
      .filter(Boolean);
    if (hints.some((h) => NOT.test(h))) return false;
    if (hints.some((h) => HINT.test(h.trim()))) return true;
    return (el.inputMode === "numeric" || el.type === "tel" || el.type === "number") && [6, 8].includes(el.maxLength);
  }

  function setValue(el, value) {
    el.focus();
    // The prototype's setter, so frameworks that watch the value (React, …) notice the change.
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(el, value);
    el.dispatchEvent(new InputEvent("input", { bubbles: true, composed: true, inputType: "insertText", data: value }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function fill(field, code) {
    const boxes = boxGroup(field);
    if (boxes && boxes.length >= code.length) {
      boxes.forEach((b, i) => setValue(b, code[i] ?? ""));
      boxes[code.length - 1].focus();
    } else {
      setValue(field, code);
    }
  }

  // For the popup (chrome.scripting runs these in this same isolated world).
  globalThis.totemProbe = () => {
    const active = deepActive();
    return {
      focused: isTextField(active),
      code: isCodeField(active) || [...document.querySelectorAll("input")].some(isCodeField),
      host: location.hostname,
    };
  };
  globalThis.totemFill = (code) => {
    const active = deepActive();
    const field = isTextField(active) ? active : [...document.querySelectorAll("input")].find(isCodeField);
    if (!field) return false;
    fill(field, code);
    return true;
  };

  // ---- The button inside code boxes ----

  let target = null;
  let busy = false;
  let hideTimer = 0;
  let ui = null;

  function buildUi() {
    const host = document.createElement("totem-fill");
    host.style.cssText = "all: initial; position: fixed; z-index: 2147483647; top: 0; left: 0;";
    const root = host.attachShadow({ mode: "closed" });
    const style = document.createElement("style");
    style.textContent = `
      button { all: initial; box-sizing: border-box; width: 24px; height: 24px; border-radius: 6px; cursor: pointer;
        display: grid; place-items: center; box-shadow: 0 1px 3px rgba(0,0,0,.3); }
      button:hover { filter: brightness(1.1); }
      svg { width: 24px; height: 24px; }
      .tip { position: absolute; top: 28px; right: 0; white-space: nowrap; max-width: 360px; overflow: hidden; text-overflow: ellipsis;
        font: 13px/1.35 "Segoe UI", system-ui, sans-serif; padding: 6px 10px; border-radius: 8px;
        background: #1d1b19; color: #efe9e3; box-shadow: 0 2px 8px rgba(0,0,0,.3); }
      .tip.error { background: #b3261e; color: #fff; }
      .tip[hidden] { display: none; }`;
    const button = document.createElement("button");
    button.title = "Fill the 2FA code with Totem";
    button.setAttribute("aria-label", button.title);
    button.append(logo());
    const tip = document.createElement("div");
    tip.className = "tip";
    tip.hidden = true;
    root.append(style, button, tip);
    // Keep focus in the code box, so the page doesn't see it lose focus.
    button.addEventListener("mousedown", (e) => e.preventDefault());
    button.addEventListener("click", onClick);
    host.addEventListener("mouseenter", () => clearTimeout(hideTimer));
    document.documentElement.append(host);
    return { host, tip };
  }

  /** The Totem tile (desktop/icon.svg), small. */
  function logo() {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 1024 1024");
    const shapes = [
      ["rect", { width: 1024, height: 1024, rx: 224, fill: "#b5652b" }],
      ["rect", { x: 332, y: 150, width: 360, height: 200, rx: 48, fill: "#fbeee2" }],
      ["rect", { x: 272, y: 392, width: 480, height: 240, rx: 56, fill: "#fbeee2" }],
      ["circle", { cx: 512, cy: 482, r: 54, fill: "#b5652b" }],
      ["path", { d: "M482 520 h60 l18 82 h-96 z", fill: "#b5652b" }],
      ["rect", { x: 332, y: 674, width: 360, height: 200, rx: 48, fill: "#fbeee2" }],
    ];
    for (const [tag, attrs] of shapes) {
      const el = document.createElementNS(ns, tag);
      for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
      svg.append(el);
    }
    return svg;
  }

  function place() {
    if (!ui || !target) return;
    const r = target.getBoundingClientRect();
    if (r.width === 0 || r.bottom < 0 || r.top > innerHeight) {
      ui.host.style.display = "none";
      return;
    }
    ui.host.style.display = "block";
    ui.host.style.transform = `translate(${Math.round(r.right - 28)}px, ${Math.round(r.top + (r.height - 24) / 2)}px)`;
  }

  function show(field) {
    clearTimeout(hideTimer);
    // For one-box-per-digit codes, sit in the last box.
    const boxes = boxGroup(field);
    target = boxes ? boxes[boxes.length - 1] : field;
    ui ??= buildUi();
    if (!busy) setTip("");
    place();
  }

  function hide() {
    if (busy || !ui) return;
    ui.host.style.display = "none";
    target = null;
  }

  function setTip(text, cls = "") {
    ui.tip.textContent = text;
    ui.tip.className = "tip " + cls;
    ui.tip.hidden = !text;
  }

  async function onClick(e) {
    if (!e.isTrusted || !target || busy) return;
    const field = target;
    busy = true;
    setTip("Asking your phone…");
    const slow = setTimeout(() => setTip("Waiting… (Windows Hello, or Approve on your phone)"), 2500);
    try {
      const r = await chrome.runtime.sendMessage({ type: "fill-here" });
      if (r?.code) {
        fill(field, r.code);
        setTip(`Filled ${r.name}`);
        hideTimer = setTimeout(hide, 1500);
      } else if (r?.picking) {
        setTip("");
      } else {
        setTip(r?.error ?? "No answer from Totem", "error");
      }
    } catch (err) {
      setTip(String(err.message ?? err), "error");
    } finally {
      clearTimeout(slow);
      busy = false;
    }
  }

  document.addEventListener("focusin", (e) => {
    const el = e.composedPath()[0];
    if (isCodeField(el)) show(el);
  }, true);
  document.addEventListener("focusout", () => {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(hide, 200);
  }, true);
  addEventListener("scroll", () => requestAnimationFrame(place), true);
  addEventListener("resize", () => requestAnimationFrame(place));

  // A code box that already has focus when the page loads.
  const active = deepActive();
  if (isCodeField(active)) show(active);
})();
