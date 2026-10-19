// Answers the Totem button inside code boxes (content.js): finds the account for the page the box
// is on and gets its code. When it can't tell which account, it opens the toolbar popup instead.
import { ask, hostOf, matchesSite } from "./totem.js";

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg?.type !== "fill-here" || !sender.tab) return false;
  fillHere(sender).then(sendResponse, (e) => sendResponse({ error: e.message }));
  return true;
});

async function fillHere(sender) {
  // The frame's address as the browser saw it, not anything the page told us.
  const host = hostOf(sender.url);
  const usable = (accounts) => accounts.filter((a) => a.policy !== "never" && matchesSite(a, host));
  let matches = usable((await ask({ type: "accounts" })).accounts);
  // Maybe the account was added on the phone after the list was cached.
  if (matches.length === 0) matches = usable((await ask({ type: "accounts", refresh: true })).accounts);
  if (matches.length !== 1) {
    try {
      await chrome.action.openPopup();
      return { picking: true };
    } catch {
      return { error: matches.length ? "Several accounts match: pick one with the Totem button in the toolbar." : `No account matches ${host}: pick one with the Totem button in the toolbar.` };
    }
  }
  const { code } = await ask({ type: "code", account: matches[0].id });
  return { code, name: matches[0].title };
}
