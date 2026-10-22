// Totem relay on Cloudflare Workers: same wire protocol as the Rust relay in ../relay
// (see protocol/src/relay.rs). One Durable Object holds every connection; it uses the
// WebSocket Hibernation API so idle phones and PCs cost nothing between requests.
//
// Stored (Durable Object storage): each phone's FCM token and allow list, plus messages
// waiting up to HOLD_FOR_MS for an offline device. Never anything but device ids and Noise
// ciphertext.

import { DurableObject } from "cloudflare:workers";

export interface Env {
  RELAY: DurableObjectNamespace<Relay>;
  /** Firebase service-account JSON (wrangler secret put FCM_SERVICE_ACCOUNT). Optional. */
  FCM_SERVICE_ACCOUNT?: string;
}

const AUTH_CONTEXT = new TextEncoder().encode("totem-relay-auth-v1");
const MAX_DATA_LEN = 16 * 1024;
const MAX_FRAME_LEN = MAX_DATA_LEN + 1024;
// Covers a cold phone start, and outlasts the PC's 25 s wait (client PHONE_TIMEOUT).
const HOLD_FOR_MS = 30_000;
const AUTH_TIMEOUT_MS = 10_000;
const MIN_WAKE_INTERVAL_MS = 2_000;
const RATE_LIMIT = 60;
const RATE_WINDOW_MS = 10_000;
const MAX_QUEUE = 32;
const MAX_PEERS = 64;

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname === "/health") return new Response("ok");
    if (url.pathname !== "/v1/ws") return new Response("not found", { status: 404 });
    if (req.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("expected a WebSocket", { status: 426 });
    }
    return env.RELAY.get(env.RELAY.idFromName("relay")).fetch(req);
  },
} satisfies ExportedHandler<Env>;

/** Per-socket state; survives hibernation via serializeAttachment. */
interface Conn {
  serial: number;
  nonce: string; // base64
  openedAt: number;
  id?: string; // set after login
  windowStart: number;
  count: number;
}

interface Held {
  at: number;
  frame: string;
}

type ClientMsg =
  | { t: "auth"; pubkey: string; sig: string }
  | { t: "register_push"; fcm: string }
  | { t: "allow"; peers: string[] }
  | { t: "send"; to: string; sid: string; data: string };

export class Relay extends DurableObject<Env> {
  private lastWake = new Map<string, number>();
  private fcmToken?: { token: string; expires: number };

  async fetch(_req: Request): Promise<Response> {
    const [client, server] = Object.values(new WebSocketPair());
    const serial = ((await this.ctx.storage.get<number>("serial")) ?? 0) + 1;
    await this.ctx.storage.put("serial", serial);
    const nonce = b64(crypto.getRandomValues(new Uint8Array(32)));
    const conn: Conn = { serial, nonce, openedAt: Date.now(), windowStart: Date.now(), count: 0 };
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(conn);
    server.send(JSON.stringify({ t: "challenge", nonce }));
    await this.scheduleAlarm(Date.now() + AUTH_TIMEOUT_MS);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const conn = ws.deserializeAttachment() as Conn;
    if (typeof raw !== "string") return;
    if (raw.length > MAX_FRAME_LEN) {
      ws.close(1009, "message too large");
      return;
    }
    let msg: ClientMsg;
    try {
      msg = JSON.parse(raw);
    } catch {
      if (!conn.id) return this.fail(ws, "auth_failed", "expected auth");
      return send(ws, error("bad_request", "unreadable message"));
    }

    if (!conn.id) {
      if (msg.t !== "auth") return this.fail(ws, "auth_failed", "expected auth");
      const id = await verifyAuth(msg, conn.nonce);
      if (typeof id !== "string") return this.fail(ws, "auth_failed", id.error);
      conn.id = id;
      ws.serializeAttachment(conn);
      send(ws, { t: "ready", id });
      await this.flushHeld(id, ws);
      return;
    }

    const now = Date.now();
    if (now - conn.windowStart > RATE_WINDOW_MS) {
      conn.windowStart = now;
      conn.count = 0;
    }
    conn.count++;
    ws.serializeAttachment(conn);
    if (conn.count > RATE_LIMIT) return send(ws, error("rate_limited", "slow down"));

    switch (msg.t) {
      case "auth":
        return send(ws, error("bad_request", "already logged in"));
      case "register_push":
        if (typeof msg.fcm !== "string" || msg.fcm.length > 4096) return send(ws, error("bad_request", "bad token"));
        await this.ctx.storage.put(`fcm:${conn.id}`, msg.fcm);
        return;
      case "allow":
        if (!Array.isArray(msg.peers)) return send(ws, error("bad_request", "bad peers"));
        await this.ctx.storage.put(`allow:${conn.id}`, msg.peers.filter((p) => typeof p === "string").slice(0, MAX_PEERS));
        return;
      case "send": {
        const err = await this.deliver(conn.id, msg);
        if (err) send(ws, err);
        return;
      }
      default:
        return send(ws, error("bad_request", "unreadable message"));
    }
  }

  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    try {
      ws.close(code === 1005 ? 1000 : code, "bye");
    } catch {
      // already closed
    }
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    try {
      ws.close(1011, "error");
    } catch {
      // already closed
    }
  }

  /** Drops sockets that never logged in, and expired held messages. */
  async alarm(): Promise<void> {
    const now = Date.now();
    let next = Infinity;
    for (const ws of this.ctx.getWebSockets()) {
      const c = ws.deserializeAttachment() as Conn;
      if (c.id) continue;
      if (now - c.openedAt >= AUTH_TIMEOUT_MS) ws.close(1008, "login timed out");
      else next = Math.min(next, c.openedAt + AUTH_TIMEOUT_MS);
    }
    const held = await this.ctx.storage.list<Held[]>({ prefix: "held:" });
    for (const [key, queue] of held) {
      const fresh = queue.filter((h) => now - h.at < HOLD_FOR_MS);
      if (fresh.length === 0) await this.ctx.storage.delete(key);
      else {
        if (fresh.length !== queue.length) await this.ctx.storage.put(key, fresh);
        next = Math.min(next, Math.min(...fresh.map((h) => h.at)) + HOLD_FOR_MS);
      }
    }
    if (next !== Infinity) await this.ctx.storage.setAlarm(next);
  }

  private async scheduleAlarm(at: number): Promise<void> {
    const current = await this.ctx.storage.getAlarm();
    if (current === null || current > at) await this.ctx.storage.setAlarm(at);
  }

  private fail(ws: WebSocket, code: string, msg: string): void {
    send(ws, error(code, msg));
    ws.close(1008, code);
  }

  /** The newest logged-in socket for a device (a newer login replaces older ones). */
  private socketFor(id: string): WebSocket | undefined {
    let best: WebSocket | undefined;
    let bestSerial = -1;
    for (const ws of this.ctx.getWebSockets()) {
      const c = ws.deserializeAttachment() as Conn;
      if (c.id === id && c.serial > bestSerial && ws.readyState === WebSocket.OPEN) {
        best = ws;
        bestSerial = c.serial;
      }
    }
    return best;
  }

  private async deliver(from: string, msg: { to: string; sid: string; data: string }): Promise<object | undefined> {
    const { to, sid, data } = msg;
    if (typeof to !== "string" || typeof sid !== "string" || typeof data !== "string") {
      return error("bad_request", "unreadable message");
    }
    if (data.length > MAX_DATA_LEN) return error("too_large", "message too large");
    const allowed = await this.ctx.storage.get<string[]>(`allow:${to}`);
    if (allowed && !allowed.includes(from)) return error("not_allowed", "that device has not paired with you");

    const frame = JSON.stringify({ t: "msg", from, sid, data });
    const target = this.socketFor(to);
    if (target) {
      try {
        target.send(frame);
        return undefined;
      } catch {
        // fall through: hold it
      }
    }
    // Offline: hold it and wake the device.
    const now = Date.now();
    const key = `held:${to}`;
    const queue = ((await this.ctx.storage.get<Held[]>(key)) ?? []).filter((h) => now - h.at < HOLD_FOR_MS);
    if (queue.length >= MAX_QUEUE) return error("queue_full", "too many messages waiting for that device");
    queue.push({ at: now, frame });
    await this.ctx.storage.put(key, queue);
    await this.scheduleAlarm(now + HOLD_FOR_MS);
    this.ctx.waitUntil(this.wake(to));
    return undefined;
  }

  private async flushHeld(id: string, ws: WebSocket): Promise<void> {
    const key = `held:${id}`;
    const queue = await this.ctx.storage.get<Held[]>(key);
    if (!queue) return;
    await this.ctx.storage.delete(key);
    const now = Date.now();
    for (const h of queue) if (now - h.at < HOLD_FOR_MS) ws.send(h.frame);
  }

  private async wake(id: string): Promise<void> {
    const token = await this.ctx.storage.get<string>(`fcm:${id}`);
    if (!token || !this.env.FCM_SERVICE_ACCOUNT) return;
    const now = Date.now();
    if (now - (this.lastWake.get(id) ?? 0) < MIN_WAKE_INTERVAL_MS) return;
    this.lastWake.set(id, now);
    try {
      const account = JSON.parse(this.env.FCM_SERVICE_ACCOUNT) as ServiceAccount;
      const bearer = await this.accessToken(account);
      const res = await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
        method: "POST",
        headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
        body: JSON.stringify({
          message: { token, data: { kind: "wake" }, android: { priority: "HIGH", ttl: "30s" } },
        }),
      });
      if (!res.ok) console.warn(`wake push failed: ${res.status} ${await res.text()}`);
    } catch (e) {
      console.warn(`wake push failed: ${e}`);
    }
  }

  private async accessToken(account: ServiceAccount): Promise<string> {
    if (this.fcmToken && this.fcmToken.expires > Date.now()) return this.fcmToken.token;
    const iat = Math.floor(Date.now() / 1000);
    const enc = (o: object) => b64url(new TextEncoder().encode(JSON.stringify(o)));
    const unsigned = `${enc({ alg: "RS256", typ: "JWT" })}.${enc({
      iss: account.client_email,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: account.token_uri,
      iat,
      exp: iat + 3600,
    })}`;
    const der = unb64(account.private_key.replace(/-----[^-]+-----|\s/g, ""));
    const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
    const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)));
    const res = await fetch(account.token_uri, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: `${unsigned}.${b64url(sig)}`,
      }),
    });
    if (!res.ok) throw new Error(`token endpoint: ${res.status} ${await res.text()}`);
    const token = ((await res.json()) as { access_token: string }).access_token;
    this.fcmToken = { token, expires: Date.now() + 50 * 60_000 };
    return token;
  }
}

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri: string;
}

/** Checks the login signature; returns the device id. */
async function verifyAuth(msg: { pubkey: unknown; sig: unknown }, nonce: string): Promise<string | { error: string }> {
  let pubkey: Uint8Array, sig: Uint8Array;
  try {
    pubkey = unb64(String(msg.pubkey));
    sig = unb64(String(msg.sig));
  } catch {
    return { error: "bad public key" };
  }
  if (pubkey.length !== 32) return { error: "bad public key" };
  if (sig.length !== 64) return { error: "bad signature" };
  let ok = false;
  try {
    const key = await crypto.subtle.importKey("raw", pubkey, { name: "Ed25519" }, false, ["verify"]);
    const message = new Uint8Array([...AUTH_CONTEXT, ...unb64(nonce)]);
    ok = await crypto.subtle.verify("Ed25519", key, sig, message);
  } catch {
    return { error: "bad public key" };
  }
  if (!ok) return { error: "signature does not match" };
  return deviceId(pubkey);
}

/** First 16 bytes of SHA-256 of the Ed25519 public key, as hex (protocol/src/relay.rs). */
export async function deviceId(pubkey: Uint8Array): Promise<string> {
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", pubkey));
  return [...hash.slice(0, 16)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function error(code: string, msg: string) {
  return { t: "error", code, msg };
}

function send(ws: WebSocket, m: object): void {
  try {
    ws.send(JSON.stringify(m));
  } catch {
    // socket gone
  }
}

function b64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

function b64url(bytes: Uint8Array): string {
  return b64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function unb64(s: string): Uint8Array {
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
}
