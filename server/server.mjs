// The ClientTag server: ClientTag users say which client they're really on, and look up the
// players around them. No dependencies - Node 18+ only. Everything is kept in memory; a restart
// just means everyone logs in again on their next sync.
//
//   POST /v1/login  { name, serverId }  -> { token, uuid, expiresIn }
//        after the client did a Mojang session server join with serverId (checked via hasJoined)
//   POST /v1/sync   { clients: [id], lookup: [uuid] } -> { users: { uuid: [id] } }
//        (Authorization: Bearer <token>) - refreshes our presence, answers for live users only
//   POST /v1/leave  -> drops our presence
//   GET  /health    -> ok
//   GET  /admin     -> request log and User-Agent stats (HTTP Basic auth, password = ADMIN_PASSWORD)

import { createServer } from "node:http";
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { readFileSync } from "node:fs";

const HOST = process.env.HOST ?? "127.0.0.1";
const PORT = Number(process.env.PORT ?? 8787);
// Behind a Cloudflare Tunnel every request comes from cloudflared, so the real address is in
// CF-Connecting-IP. It's only believed from the machines in TRUSTED_PROXIES (where cloudflared
// runs; this machine by default), so other devices on the network can't fake it.
const TRUST_CF = (process.env.TRUST_CF ?? "1") === "1";
const TRUSTED_PROXIES = new Set(["127.0.0.1", "::1", ...(process.env.TRUSTED_PROXIES ?? "").split(",")]
  .map((ip) => ip.trim())
  .filter(Boolean)
  .flatMap((ip) => (ip.includes(":") ? [ip] : [ip, `::ffff:${ip}`])));

// The /admin page is off unless a password is set.
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD ?? "";
const ADMIN_PAGE = readFileSync(new URL("./admin.html", import.meta.url), "utf8");

const TOKEN_TTL_MS = 6 * 60 * 60 * 1000;
const PRESENCE_TTL_MS = 90 * 1000; // clients sync every 30 s
const SERVER_ID_TTL_MS = 5 * 60 * 1000;
const MAX_BODY = 64 * 1024;
const MAX_LOOKUP = 512;
const LOG_SIZE = 2000;
const MAX_AGENTS = 5000;
const CLIENT_IDS = new Set(["polyplus", "lunar", "dawn", "essential", "norisk", "labymod"]);

// token -> { uuid, expires }
const tokens = new Map();
// uuid -> { clients, expires }
const presence = new Map();
// serverIds already used for a login, so a join can't be replayed
const usedServerIds = new Map();
// rate limit buckets: key -> { count, resetAt }
const buckets = new Map();
// the last LOG_SIZE requests, oldest first: { time, method, path, status, ms, ua }
const requestLog = [];
// User-Agent -> { count, first, last }, since startup
const agents = new Map();
const startedAt = Date.now();

function record(entry) {
  requestLog.push(entry);
  if (requestLog.length > LOG_SIZE) requestLog.shift();
  let agent = agents.get(entry.ua);
  if (!agent) {
    // Forget the oldest agent rather than grow forever on junk User-Agents.
    if (agents.size >= MAX_AGENTS) agents.delete(agents.keys().next().value);
    agent = { count: 0, first: entry.time };
    agents.set(entry.ua, agent);
  }
  agent.count++;
  agent.last = entry.time;
}

function sha256(text) {
  return createHash("sha256").update(text).digest();
}

function isAdmin(req) {
  const header = req.headers.authorization ?? "";
  if (!header.startsWith("Basic ")) return false;
  const decoded = Buffer.from(header.slice(6), "base64").toString("utf8");
  const password = decoded.slice(decoded.indexOf(":") + 1);
  return timingSafeEqual(sha256(password), sha256(ADMIN_PASSWORD));
}

function admin(req, res, path) {
  if (!ADMIN_PASSWORD) return send(res, 404, { error: "not found" });
  if (!isAdmin(req)) {
    res.writeHead(401, { "WWW-Authenticate": 'Basic realm="ClientTag admin", charset="UTF-8"' });
    return res.end();
  }
  if (path === "/admin") {
    res.writeHead(200, {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "Content-Security-Policy": "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'",
    });
    return res.end(ADMIN_PAGE);
  }
  if (path === "/admin/api") {
    const now = Date.now();
    const clients = {};
    let live = 0;
    for (const entry of presence.values()) {
      if (entry.expires <= now) continue;
      live++;
      for (const id of entry.clients.length ? entry.clients : ["none"]) clients[id] = (clients[id] ?? 0) + 1;
    }
    return send(res, 200, {
      startedAt,
      now,
      live,
      tokens: tokens.size,
      clients,
      agents: [...agents].map(([ua, a]) => ({ ua, ...a })).sort((x, y) => y.count - x.count),
      requests: requestLog.slice().reverse(),
    }, { "Cache-Control": "no-store" });
  }
  return send(res, 404, { error: "not found" });
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function limited(key, max, windowMs) {
  const now = Date.now();
  let bucket = buckets.get(key);
  if (!bucket || now >= bucket.resetAt) {
    bucket = { count: 0, resetAt: now + windowMs };
    buckets.set(key, bucket);
  }
  return ++bucket.count > max;
}

function dashed(id) {
  return `${id.slice(0, 8)}-${id.slice(8, 12)}-${id.slice(12, 16)}-${id.slice(16, 20)}-${id.slice(20)}`.toLowerCase();
}

async function login(body, ip) {
  if (limited(`login:${ip}`, 10, 60_000)) return [429, { error: "slow down" }];
  const { name, serverId } = body;
  if (typeof name !== "string" || !/^\w{1,16}$/.test(name)) return [400, { error: "bad name" }];
  if (typeof serverId !== "string" || !/^[A-Za-z]{32}$/.test(serverId)) return [400, { error: "bad serverId" }];
  if (usedServerIds.has(serverId)) return [401, { error: "serverId already used" }];
  usedServerIds.set(serverId, Date.now() + SERVER_ID_TTL_MS);

  const url = `https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${serverId}`;
  const response = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  if (response.status !== 200) return [401, { error: "session not verified" }];
  const profile = await response.json();
  if (typeof profile?.id !== "string" || !/^[0-9a-fA-F]{32}$/.test(profile.id)) return [502, { error: "bad Mojang response" }];

  const uuid = dashed(profile.id);
  const token = randomBytes(32).toString("base64url");
  tokens.set(token, { uuid, expires: Date.now() + TOKEN_TTL_MS });
  return [200, { token, uuid, expiresIn: TOKEN_TTL_MS / 1000 }];
}

function sync(session, body) {
  if (limited(`sync:${session.uuid}`, 30, 60_000)) return [429, { error: "slow down" }];
  const clients = Array.isArray(body.clients)
    ? [...new Set(body.clients.filter((id) => CLIENT_IDS.has(id)))]
    : [];
  presence.set(session.uuid, { clients, expires: Date.now() + PRESENCE_TTL_MS });

  const users = {};
  if (Array.isArray(body.lookup)) {
    const now = Date.now();
    for (const raw of body.lookup.slice(0, MAX_LOOKUP)) {
      if (typeof raw !== "string") continue;
      const uuid = raw.toLowerCase();
      if (!UUID_RE.test(uuid)) continue;
      const entry = presence.get(uuid);
      if (entry && entry.expires > now) users[uuid] = entry.clients;
    }
  }
  return [200, { users }];
}

function authenticate(req) {
  const header = req.headers.authorization ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : null;
  const session = token && tokens.get(token);
  if (!session || session.expires <= Date.now()) return null;
  return session;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        reject(new Error("too large"));
        req.destroy();
      } else {
        chunks.push(chunk);
      }
    });
    req.on("end", () => {
      try {
        const text = Buffer.concat(chunks).toString("utf8");
        resolve(text ? JSON.parse(text) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on("error", reject);
  });
}

function send(res, status, body, headers = {}) {
  const text = JSON.stringify(body);
  res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(text), ...headers });
  res.end(text);
}

const server = createServer(async (req, res) => {
  const viaTunnel = TRUSTED_PROXIES.has(req.socket.remoteAddress);
  const ip = (TRUST_CF && viaTunnel && req.headers["cf-connecting-ip"]) || req.socket.remoteAddress;
  const path = (req.url ?? "/").split("?")[0];
  const start = Date.now();
  // Everything but the admin page itself goes in the request log - by User-Agent, not by player.
  if (!path.startsWith("/admin")) {
    res.on("finish", () => record({
      time: start,
      method: String(req.method).slice(0, 10),
      path: path.slice(0, 200),
      status: res.statusCode,
      ms: Date.now() - start,
      ua: String(req.headers["user-agent"] ?? "(none)").slice(0, 300),
    }));
  }
  try {
    if (req.method === "GET" && (path === "/admin" || path === "/admin/api")) return admin(req, res, path);
    if (req.method === "GET" && path === "/health") return send(res, 200, { ok: true });
    if (req.method !== "POST") return send(res, 404, { error: "not found" });

    let body;
    try {
      body = await readBody(req);
    } catch {
      return send(res, 400, { error: "bad body" });
    }
    if (typeof body !== "object" || body === null) return send(res, 400, { error: "bad body" });

    if (path === "/v1/login") return send(res, ...(await login(body, ip)));

    const session = authenticate(req);
    if (!session) return send(res, 401, { error: "unauthorized" });
    if (path === "/v1/sync") return send(res, ...sync(session, body));
    if (path === "/v1/leave") {
      presence.delete(session.uuid);
      return send(res, 200, {});
    }
    return send(res, 404, { error: "not found" });
  } catch (e) {
    console.error(`${req.method} ${req.url} failed:`, e);
    if (!res.headersSent) send(res, 500, { error: "internal error" });
  }
});

// Drop everything that has expired once a minute.
setInterval(() => {
  const now = Date.now();
  for (const map of [tokens, presence]) {
    for (const [key, value] of map) if (value.expires <= now) map.delete(key);
  }
  for (const [key, expires] of usedServerIds) if (expires <= now) usedServerIds.delete(key);
  for (const [key, bucket] of buckets) if (bucket.resetAt <= now) buckets.delete(key);
}, 60_000).unref();

server.listen(PORT, HOST, () => {
  console.log(`ClientTag server listening on http://${HOST}:${PORT}`);
  if (!ADMIN_PASSWORD) console.log("Set ADMIN_PASSWORD to turn on the /admin page.");
});
