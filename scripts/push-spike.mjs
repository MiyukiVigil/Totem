#!/usr/bin/env node
// M0 push spike: sends high-priority FCM data pushes to the phone and times how long it takes for
// the phone to wake up, compute a code and POST it back to this script over the LAN.
//
//   node scripts/push-spike.mjs --token <fcm token> [--count 5] [--interval 3000] [--label awake]
//                               [--key ~/.secrets/<service-account>.json] [--host 192.168.0.102]
//
// No dependencies. The service-account key never leaves this machine except to Google's token
// endpoint, and must stay outside the repo.

import { createServer } from "node:http";
import { createSign, createHmac, randomUUID } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import { networkInterfaces, homedir } from "node:os";
import { join } from "node:path";

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith("--") ? [...acc, [a.slice(2), all[i + 1]]] : acc), []),
);
const PORT = 8787;
const count = Number(args.count ?? 5);
const interval = Number(args.interval ?? 3000);
const label = args.label ?? "run";
const token = args.token ?? process.env.TOTEM_FCM_TOKEN;
if (!token) throw new Error("pass --token (the app logs it under the TotemPush tag)");

function defaultKey() {
  const dir = join(homedir(), ".secrets");
  const f = readdirSync(dir).find((n) => n.includes("firebase-adminsdk") && n.endsWith(".json"));
  if (!f) throw new Error(`no firebase-adminsdk key in ${dir}; pass --key`);
  return join(dir, f);
}
const key = JSON.parse(readFileSync(args.key ?? process.env.TOTEM_FCM_KEY ?? defaultKey(), "utf8"));

function lanAddress() {
  for (const list of Object.values(networkInterfaces()))
    for (const a of list ?? []) if (a.family === "IPv4" && !a.internal && a.address.startsWith("192.168.")) return a.address;
  throw new Error("no LAN IPv4 address found; pass --host");
}
const host = args.host ?? lanAddress();

const b64url = (b) => Buffer.from(b).toString("base64url");
async function accessToken() {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(JSON.stringify({
    iss: key.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: key.token_uri,
    iat: now,
    exp: now + 3600,
  }));
  const signature = createSign("RSA-SHA256").update(`${header}.${claims}`).sign(key.private_key, "base64url");
  const res = await fetch(key.token_uri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${header}.${claims}.${signature}`,
    }),
  });
  if (!res.ok) throw new Error(`token endpoint: ${res.status} ${await res.text()}`);
  return (await res.json()).access_token;
}

function totp(unixTime) {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(unixTime / 30)));
  const h = createHmac("sha1", "12345678901234567890").update(counter).digest();
  const o = h[h.length - 1] & 15;
  return String((((h[o] & 127) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3]) % 1e6).padStart(6, "0");
}

const waiting = new Map(); // req -> { resolve, sentMs }
const server = createServer((req, res) => {
  if (req.method !== "POST" || req.url !== "/reply") return res.writeHead(404).end();
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    res.writeHead(204).end();
    const reply = JSON.parse(body);
    const w = waiting.get(reply.req);
    if (w) w.resolve({ ...reply, pcReceivedMs: Date.now() });
  });
});
await new Promise((r) => server.listen(PORT, "0.0.0.0", r));

const bearer = await accessToken();
console.log(`[${label}] sending ${count} pings to ${token.slice(0, 12)}…, replies to http://${host}:${PORT}/reply`);

const results = [];
for (let i = 0; i < count; i++) {
  const reqId = randomUUID();
  const reply = new Promise((resolve) => waiting.set(reqId, { resolve }));
  const sentMs = Date.now();
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${key.project_id}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${bearer}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      message: {
        token,
        data: { kind: "ping", req: reqId, reply: `http://${host}:${PORT}/reply` },
        android: { priority: "HIGH", ttl: "30s" },
      },
    }),
  });
  if (!res.ok) throw new Error(`FCM send: ${res.status} ${await res.text()}`);
  const acceptedMs = Date.now();

  const r = await Promise.race([reply, new Promise((resolve) => setTimeout(() => resolve(null), Number(args.timeout ?? 30000)))]);
  waiting.delete(reqId);
  if (!r) {
    console.log(`  #${i + 1} TIMEOUT (no reply in ${Number(args.timeout ?? 30000) / 1000}s)`);
    results.push(null);
  } else {
    const total = r.pcReceivedMs - sentMs;
    const codeOk = r.code === totp(r.code_time);
    console.log(
      `  #${i + 1} ${String(total).padStart(5)} ms total  (FCM accept ${acceptedMs - sentMs} ms)  ` +
        `priority=${r.priority}/${r.original_priority} idle=${r.device_idle} saver=${r.power_save} ` +
        `screen=${r.interactive ? "on" : "off"} code=${r.code} ${codeOk ? "✓" : "✗ MISMATCH"}`,
    );
    results.push(total);
  }
  if (i < count - 1) await new Promise((r) => setTimeout(r, interval));
}

const ok = results.filter((x) => x !== null).sort((a, b) => a - b);
if (ok.length) {
  const median = ok[Math.floor(ok.length / 2)];
  console.log(`[${label}] ${ok.length}/${count} replied · median ${median} ms · worst ${ok[ok.length - 1]} ms`);
} else {
  console.log(`[${label}] 0/${count} replied`);
}
server.close();
