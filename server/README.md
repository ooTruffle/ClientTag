# ClientTag server

ClientTag users tell this server which client they're really on (Lunar, NoRisk, ... or none),
and look up the players around them, so other ClientTag users only see their real tag. They also
say which clients they've spotted other players on, for the admin page's statistics.

It's one file with no dependencies and keeps everything in memory, except the list of players
who have logged in (see `PLAYERS_FILE`), the client history for the admin graphs (see
`HISTORY_FILE`) and which clients spotted players were seen on (see `SEEN_FILE`). Node 18 or newer.

```bash
node server.mjs
```

| Variable   | Default     | Meaning                                                                       |
|------------|-------------|-------------------------------------------------------------------------------|
| `HOST`     | `127.0.0.1` | Address to listen on. `0.0.0.0` also lets devices on your network open it (e.g. the admin page at `http://<LAN IP>:8787/admin`); don't port-forward it. |
| `PORT`     | `8787`      | Port to listen on.                                                            |
| `TRUST_CF` | `1`         | Read the client IP from `CF-Connecting-IP` (for login rate limits), only on requests from cloudflared - see `TRUSTED_PROXIES`. |
| `TRUSTED_PROXIES` | (unset) | Comma-separated IPs of other machines running cloudflared for this server, e.g. a Proxmox host `192.168.40.100`. This machine is always trusted. |
| `ADMIN_PASSWORD` | (unset) | Turns on the `/admin` page, behind HTTP Basic auth with this password (any username). |
| `PLAYERS_FILE` | `players.json` next to `server.mjs` | Every player who has ever logged in (name, UUID, first seen, last login, ClientTag icon color, every client they've reported). |
| `HISTORY_FILE` | `history.json` next to `server.mjs` | The number of live ClientTag users and spotted players on each client, every 5 minutes, for the last 90 days (counts only, no names or UUIDs). About 5 MB when full. |
| `SEEN_FILE` | `seen.json` next to `server.mjs` | Every client each spotted player was ever seen on, keyed by a salted hash of their UUID (the salt is in the file). It can't be turned back into a list of players, but someone with the file and a UUID could check that one player. At most 500,000 players. |

## Admin page

`https://clienttags.fluffykiwi.net/admin` shows the live users per client, every User-Agent
seen since startup with its request count, and the last 2000 requests (time, path, status,
duration, User-Agent). ClientTag reports itself as `ClientTag/<version> (Minecraft <version>;
Fabric|Ornithe)`, so anything else stands out. Only User-Agents are logged - no IPs, names or
UUIDs - and it's all in memory, gone on restart.

It also lists every player who has logged in (from `PLAYERS_FILE`), whether they're online and
showing their ClientTag icon, with a color picker for that icon. Their Client column shows the
client they're on now if they're online, then in grey every other client they've used (the
filter box matches client names too). Players are white until given a
color; other ClientTag users pick up a change on their next heartbeat (~30 s).

Graphs show which clients people use, both for ClientTag users (what they say they're on) and
for **spotted players**: everyone else ClientTag users see, by the tags ClientTag found on them.

- **Clients over time**: a line per client, from a count taken every 5 minutes (on the clock,
  skipped in the first 90 s after a restart while everyone signs back in). Switch between
  *ClientTag users* and *Spotted players* above it. Day shows every count; Week, Month and 90
  days show the peak per 1, 3 and 12 hours. Hover for the numbers at a point, click a client below
  the graph to hide or show it. Gaps are times the server was off.
- **Players who have used each client**: every known ClientTag user counted once for each client
  they've ever reported, `none` meaning ClientTag on its own. Players from before this was added
  are counted once they next sign in.
- **Spotted players on each client**: every spotted player counted once for each client they've
  ever been seen on. Players seen on none of them aren't counted, here or in the graph.

A spotted player is counted once however many ClientTag users see them. Anyone who has ever
signed in to this server is left out, since they report their own clients and the tags others
see on them are mostly ClientTag's own. Like everything a client sends, sightings are taken on
trust: a signed-in user could send made-up ones.

For a second lock, put `/admin*` behind Cloudflare Access too.

## Running it behind a Cloudflare Tunnel

```yaml
# ~/.cloudflared/config.yml
tunnel: <tunnel id>
credentials-file: /path/to/<tunnel id>.json
ingress:
  - hostname: clienttags.fluffykiwi.net
    service: http://127.0.0.1:8787
  - service: http_status:404
```

The mod uses `https://clienttags.fluffykiwi.net` (`PresenceApi.DEFAULT_URL`); launch with
`-Dclienttag.server=http://127.0.0.1:8787` to test against a local copy instead.

## Protocol

| Request | Body | Response |
|---|---|---|
| `POST /v1/login` | `{ name, serverId }`, sent after a Mojang session join with `serverId` | `{ token, uuid, expiresIn }` |
| `POST /v1/sync` (Bearer token) | `{ clients: ["lunar", ...], showTag, lookup: [uuid, ...], seen?: { uuid: ["lunar", ...] } }` (at most 512 each) | `{ users: { uuid: ["lunar"] }, tags: { uuid: rgb } }` |
| `POST /v1/leave` (Bearer token) | `{}` | `{}` |
| `GET /health` | | `{ ok: true }` |
| `POST /admin/color` (Basic auth, JSON from the admin page only) | `{ uuid, color: "#rrggbb" \| null }` | `{}` |
| `GET /admin/history?range=day\|week\|month\|all` (Basic auth) | | `{ from, now, step, sampleMs, points: [{ t, live, c: { client: peak }, seen, s: { client: peak } }] }` |

`tags` holds the looked-up users whose ClientTag icon should be drawn - those who turned on
*Show My ClientTag Icon* (`showTag`), and those on no other client - in their color (white,
`0xffffff`, unless the admin set one).

`seen` is the players around you the mod has found on at least one client, with those clients
(never ClientTag users). The mod collects them and sends one batch every 5 minutes (at most 512
players), riding along with its next heartbeat sync rather than a request of its own; most syncs
have no `seen`. Each batch counts as live for 6 minutes, until the next one is due, so the graph's
spotted numbers are "spotted in about the last 5 minutes". Client ids the server doesn't know are
dropped, and entries left with no client are ignored.

A sync refreshes your own presence for 90 seconds. The mod syncs at most every 5 seconds and at
least every 30 while you're on a server, and leaves when you disconnect. Only live users are ever
returned.
