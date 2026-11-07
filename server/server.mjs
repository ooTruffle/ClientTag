// The ClientTag server: ClientTag users say which client they're really on, and look up the
// players around them. No dependencies - Node 18+ only. Everything is kept in memory; a restart
// just means everyone logs in again on their next sync.
//
//   POST /v1/login  { name, serverId }  -> { token, uuid, expiresIn }
//        after the client did a Mojang session server join with serverId (checked via hasJoined)
//   POST /v1/sync   { clients: [id], showTag, lookup: [uuid], seen?: { uuid: [id] } } -> { users: { uuid: [id] }, tags: { uuid: rgb } }
//        (Authorization: Bearer <token>) - refreshes our presence, answers for live users only;
//        tags are the users whose ClientTag icon is drawn, in their color (white unless the admin set one);
//        seen is the clients the user spotted other players on (empty lists are ignored), for the statistics
//   POST /v1/leave  -> drops our presence
//   GET  /health    -> ok
//   GET  /admin     -> request log and User-Agent stats (HTTP Basic auth, password = ADMIN_PASSWORD)
//   POST /admin/color { uuid, color: "#rrggbb" | null } -> sets a player's ClientTag icon color
//   GET  /admin/history?range=day|week|month|all -> live and spotted players per client over time, for the graphs
//
// Written to disk: the list of players who ever logged in (name, UUID, first/last seen, color,
// clients they've reported) in PLAYERS_FILE, so the admin can give them colors; a count of live
// users and spotted players per client every SAMPLE_MS in HISTORY_FILE (counts only, no names or
// UUIDs); and in SEEN_FILE, the clients each spotted player was ever seen on, under a salted hash of
// their UUID rather than the UUID itself, since they never signed up for any of this.

import { createServer } from "node:http";
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { readFileSync, renameSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

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
const PLAYERS_FILE = process.env.PLAYERS_FILE ?? fileURLToPath(new URL("./players.json", import.meta.url));
const HISTORY_FILE = process.env.HISTORY_FILE ?? fileURLToPath(new URL("./history.json", import.meta.url));
const SEEN_FILE = process.env.SEEN_FILE ?? fileURLToPath(new URL("./seen.json", import.meta.url));
const MAX_SEEN_PLAYERS = 500_000;
const SAMPLE_MS = 5 * 60 * 1000;
const HISTORY_MS = 90 * 24 * 60 * 60 * 1000;
// Each graph range is drawn from at most this many points; each one is the peak of the samples in it.
const GRAPH_POINTS = 300;
// Point sizes a range can use, smallest first: 5 min, 10 min, 15 min, 30 min, 1, 2, 3, 6, 12, 24 h.
const GRAPH_STEPS = [5, 10, 15, 30, 60, 120, 180, 360, 720, 1440].map((m) => m * 60 * 1000);
const RANGES = { day: 24 * 60 * 60 * 1000, week: 7 * 24 * 60 * 60 * 1000, month: 30 * 24 * 60 * 60 * 1000, all: HISTORY_MS };

const TOKEN_TTL_MS = 6 * 60 * 60 * 1000;
const PRESENCE_TTL_MS = 90 * 1000; // clients sync every 30 s
// The mod sends spotted players in a batch every 5 minutes (plus up to a 30 s wait for its next
// heartbeat), so each batch counts until the next one is due.
const SIGHTING_TTL_MS = 6 * 60 * 1000;
const SERVER_ID_TTL_MS = 5 * 60 * 1000;
const MAX_BODY = 128 * 1024; // a full lookup plus a full seen
const MAX_LOOKUP = 512;
const LOG_SIZE = 2000;
const MAX_AGENTS = 5000;
const CLIENT_IDS = new Set(["polyplus", "lunar", "dawn", "essential", "norisk", "labymod", "cosmetica", "alpine"]);
const DEFAULT_TAG_COLOR = 0xffffff;

// token -> { uuid, expires }
const tokens = new Map();
// uuid -> { clients, showTag, expires }
const presence = new Map();
// uuid -> { name, first, last, color? } for everyone who ever logged in; kept in PLAYERS_FILE
const players = loadPlayers();
let playersSaveTimer = null;
// { t, live, c: { id: count }, seen, s: { id: count } } every SAMPLE_MS, oldest first: live ClientTag
// users (live, c) and spotted players (seen, s) per client, "none" for ClientTag users on no other client;
// kept in HISTORY_FILE
const history = loadHistory();
// spotted players (never ClientTag users) -> { clients, expires }, refreshed by whoever last saw them
const sightings = new Map();
// { salt, players: { hash: [id] } }: every client each spotted player was ever seen on; kept in SEEN_FILE
const seen = loadSeen();
let seenSaveTimer = null;
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

function loadPlayers() {
  try {
    return new Map(Object.entries(JSON.parse(readFileSync(PLAYERS_FILE, "utf8"))));
  } catch (e) {
    if (e.code !== "ENOENT") console.error(`Couldn't read ${PLAYERS_FILE}:`, e);
    return new Map();
  }
}

function loadHistory() {
  try {
    const list = JSON.parse(readFileSync(HISTORY_FILE, "utf8"));
    return Array.isArray(list) ? list.filter((s) => typeof s?.t === "number" && s.c && typeof s.c === "object") : [];
  } catch (e) {
    if (e.code !== "ENOENT") console.error(`Couldn't read ${HISTORY_FILE}:`, e);
    return [];
  }
}

function loadSeen() {
  try {
    const data = JSON.parse(readFileSync(SEEN_FILE, "utf8"));
    if (typeof data?.salt === "string" && data.players && typeof data.players === "object") {
      return { salt: data.salt, players: new Map(Object.entries(data.players)) };
    }
    console.error(`${SEEN_FILE} isn't in the expected shape; starting a new one`);
  } catch (e) {
    if (e.code !== "ENOENT") console.error(`Couldn't read ${SEEN_FILE}:`, e);
  }
  return { salt: randomBytes(16).toString("hex"), players: new Map() };
}

// Batched: sightings arrive with every heartbeat.
function saveSeen() {
  if (seenSaveTimer) return;
  seenSaveTimer = setTimeout(() => {
    seenSaveTimer = null;
    try {
      writeAtomic(SEEN_FILE, JSON.stringify({ salt: seen.salt, players: Object.fromEntries(seen.players) }));
    } catch (e) {
      console.error(`Couldn't write ${SEEN_FILE}:`, e);
    }
  }, 30_000);
}

function seenHash(uuid) {
  return createHash("sha256").update(seen.salt).update(uuid).digest("hex").slice(0, 20);
}

function writeAtomic(file, text) {
  const tmp = `${file}.tmp`;
  writeFileSync(tmp, text);
  renameSync(tmp, file);
}

// Live users per client right now; a user on several clients counts once for each.
function liveCounts(now) {
  return count(presence, now, () => true);
}

// Spotted players per client right now, leaving out any who have since turned out to run ClientTag.
function seenCounts(now) {
  return count(sightings, now, (uuid) => !isLive(presence.get(uuid), now));
}

function count(map, now, include) {
  const clients = {};
  let live = 0;
  for (const [uuid, entry] of map) {
    if (entry.expires <= now || !include(uuid)) continue;
    live++;
    for (const id of entry.clients.length ? entry.clients : ["none"]) clients[id] = (clients[id] ?? 0) + 1;
  }
  return { live, clients };
}

function isLive(entry, now) {
  return !!entry && entry.expires > now;
}

function takeSample() {
  const now = Date.now();
  // Right after a restart nobody has synced yet; that dip to zero isn't real.
  if (now - startedAt < PRESENCE_TTL_MS) return;
  const { live, clients } = liveCounts(now);
  const spotted = seenCounts(now);
  history.push({ t: now, live, c: clients, seen: spotted.live, s: spotted.clients });
  while (history.length && history[0].t < now - HISTORY_MS) history.shift();
  try {
    writeAtomic(HISTORY_FILE, JSON.stringify(history));
  } catch (e) {
    console.error(`Couldn't write ${HISTORY_FILE}:`, e);
  }
}

// The samples in a range, merged into at most GRAPH_POINTS buckets that keep each client's peak.
function graph(range) {
  const now = Date.now();
  const span = RANGES[range] ?? RANGES.day;
  const from = now - span;
  const step = GRAPH_STEPS.find((s) => s >= span / GRAPH_POINTS) ?? GRAPH_STEPS.at(-1);
  const points = [];
  let bucket = null;
  for (const sample of history) {
    if (sample.t < from) continue;
    const t = Math.floor(sample.t / step) * step;
    if (!bucket || bucket.t !== t) {
      bucket = { t, live: 0, c: {}, seen: 0, s: {} };
      points.push(bucket);
    }
    bucket.live = Math.max(bucket.live, sample.live ?? 0);
    bucket.seen = Math.max(bucket.seen, sample.seen ?? 0);
    for (const [id, n] of Object.entries(sample.c)) bucket.c[id] = Math.max(bucket.c[id] ?? 0, n);
    for (const [id, n] of Object.entries(sample.s ?? {})) bucket.s[id] = Math.max(bucket.s[id] ?? 0, n);
  }
  return { now, from, step, sampleMs: SAMPLE_MS, points };
}

// Batches writes: a burst of logins (or a restart's worth of re-logins) is one write.
function savePlayers() {
  if (playersSaveTimer) return;
  playersSaveTimer = setTimeout(() => {
    playersSaveTimer = null;
    try {
      writeAtomic(PLAYERS_FILE, JSON.stringify(Object.fromEntries(players), null, 1));
    } catch (e) {
      console.error(`Couldn't write ${PLAYERS_FILE}:`, e);
    }
  }, 2000);
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
    const { live, clients } = liveCounts(now);
    const spotted = seenCounts(now);
    // How many known players have ever been seen on each client.
    const everUsed = {};
    for (const p of players.values()) for (const id of p.clients ?? []) everUsed[id] = (everUsed[id] ?? 0) + 1;
    const everSeen = {};
    for (const ids of seen.players.values()) for (const id of ids) everSeen[id] = (everSeen[id] ?? 0) + 1;
    return send(res, 200, {
      startedAt,
      now,
      live,
      tokens: tokens.size,
      clients,
      everUsed,
      seenLive: spotted.live,
      seenClients: spotted.clients,
      everSeen,
      seenPlayers: seen.players.size,
      players: [...players].map(([uuid, p]) => {
        const entry = presence.get(uuid);
        const online = !!entry && entry.expires > now;
        // clients: every client they've ever reported; onClients: what they're on now, if online.
        return { uuid, ...p, online, showsTag: online && showsTag(entry), onClients: online ? entry.clients : null };
      }).sort((x, y) => y.last - x.last),
      agents: [...agents].map(([ua, a]) => ({ ua, ...a })).sort((x, y) => y.count - x.count),
      requests: requestLog.slice().reverse(),
    }, { "Cache-Control": "no-store" });
  }
  if (path === "/admin/history") {
    const range = new URL(req.url, "http://x").searchParams.get("range");
    return send(res, 200, graph(range), { "Cache-Control": "no-store" });
  }
  return send(res, 404, { error: "not found" });
}

async function adminColor(req, res) {
  if (!ADMIN_PASSWORD) return send(res, 404, { error: "not found" });
  if (!isAdmin(req)) return send(res, 401, { error: "unauthorized" });
  // The browser re-sends Basic auth to any site's form posts, so only take JSON from our own page:
  // cross-site JSON needs a CORS preflight this server never answers.
  const origin = req.headers.origin;
  if (!String(req.headers["content-type"] ?? "").startsWith("application/json")
      || (origin && hostOf(origin) !== req.headers.host)) {
    return send(res, 403, { error: "forbidden" });
  }
  let body;
  try {
    body = await readBody(req);
  } catch {
    return send(res, 400, { error: "bad body" });
  }
  const player = typeof body?.uuid === "string" && players.get(body.uuid.toLowerCase());
  if (!player) return send(res, 404, { error: "unknown player" });
  if (body.color === null) {
    delete player.color;
  } else if (typeof body.color === "string" && /^#[0-9a-fA-F]{6}$/.test(body.color)) {
    player.color = body.color.toLowerCase();
  } else {
    return send(res, 400, { error: "bad color" });
  }
  savePlayers();
  return send(res, 200, {});
}

function hostOf(url) {
  try {
    return new URL(url).host;
  } catch {
    return null;
  }
}

// A ClientTag user's icon is drawn if they asked for it, or if they're on no other client.
function showsTag(entry) {
  return entry.showTag || entry.clients.length === 0;
}

function tagColor(uuid) {
  const color = players.get(uuid)?.color;
  return color ? parseInt(color.slice(1), 16) : DEFAULT_TAG_COLOR;
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
  const now = Date.now();
  const player = players.get(uuid) ?? { first: now };
  player.name = typeof profile.name === "string" ? profile.name.slice(0, 16) : name;
  player.last = now;
  players.set(uuid, player);
  savePlayers();

  const token = randomBytes(32).toString("base64url");
  tokens.set(token, { uuid, expires: Date.now() + TOKEN_TTL_MS });
  return [200, { token, uuid, expiresIn: TOKEN_TTL_MS / 1000 }];
}

function sync(session, body) {
  if (limited(`sync:${session.uuid}`, 30, 60_000)) return [429, { error: "slow down" }];
  const clients = Array.isArray(body.clients)
    ? [...new Set(body.clients.filter((id) => CLIENT_IDS.has(id)))]
    : [];
  presence.set(session.uuid, { clients, showTag: body.showTag === true, expires: Date.now() + PRESENCE_TTL_MS });
  rememberClients(session.uuid, clients);
  if (body.seen && typeof body.seen === "object" && !Array.isArray(body.seen)) recordSightings(session.uuid, body.seen);

  const users = {};
  const tags = {};
  if (Array.isArray(body.lookup)) {
    const now = Date.now();
    for (const raw of body.lookup.slice(0, MAX_LOOKUP)) {
      if (typeof raw !== "string") continue;
      const uuid = raw.toLowerCase();
      if (!UUID_RE.test(uuid)) continue;
      const entry = presence.get(uuid);
      if (!entry || entry.expires <= now) continue;
      users[uuid] = entry.clients;
      if (showsTag(entry)) tags[uuid] = tagColor(uuid);
    }
  }
  return [200, { users, tags }];
}

// Adds the clients a player reports to the ones they've ever used ("none" for no other client).
function rememberClients(uuid, clients) {
  const player = players.get(uuid);
  if (!player) return;
  const used = new Set(player.clients ?? []);
  const before = used.size;
  for (const id of clients.length ? clients : ["none"]) used.add(id);
  if (used.size !== before) {
    player.clients = [...used].sort();
    savePlayers();
  }
}

// What a ClientTag user spotted other players on. ClientTag users are skipped: they report their
// own clients, and the tags others see on them are mostly ClientTag's doing.
function recordSightings(reporter, reported) {
  const now = Date.now();
  let changed = false;
  for (const [raw, ids] of Object.entries(reported).slice(0, MAX_LOOKUP)) {
    const uuid = raw.toLowerCase();
    if (!UUID_RE.test(uuid) || uuid === reporter || !Array.isArray(ids) || players.has(uuid)) continue;
    // Only players seen on a client we know count; not being seen on one says nothing.
    const clients = [...new Set(ids.filter((id) => CLIENT_IDS.has(id)))].sort();
    if (!clients.length) continue;
    sightings.set(uuid, { clients, expires: now + SIGHTING_TTL_MS });

    const hash = seenHash(uuid);
    const known = seen.players.get(hash);
    if (!known && seen.players.size >= MAX_SEEN_PLAYERS) continue;
    const all = new Set(known ?? []);
    const before = all.size;
    for (const id of clients) all.add(id);
    if (all.size !== before) {
      seen.players.set(hash, [...all].sort());
      changed = true;
    }
  }
  if (changed) saveSeen();
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
    if (req.method === "GET" && (path === "/admin" || path === "/admin/api" || path === "/admin/history")) return admin(req, res, path);
    if (req.method === "POST" && path === "/admin/color") return await adminColor(req, res);
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
  for (const map of [tokens, presence, sightings]) {
    for (const [key, value] of map) if (value.expires <= now) map.delete(key);
  }
  for (const [key, expires] of usedServerIds) if (expires <= now) usedServerIds.delete(key);
  for (const [key, bucket] of buckets) if (bucket.resetAt <= now) buckets.delete(key);
}, 60_000).unref();

// Samples line up on the clock (:00, :05, ...) so restarts don't shift the graph.
setTimeout(() => {
  takeSample();
  setInterval(takeSample, SAMPLE_MS).unref();
}, SAMPLE_MS - (Date.now() % SAMPLE_MS)).unref();

server.listen(PORT, HOST, () => {
  console.log(`ClientTag server listening on http://${HOST}:${PORT}`);
  if (!ADMIN_PASSWORD) console.log("Set ADMIN_PASSWORD to turn on the /admin page.");
});
