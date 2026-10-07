# ClientTag server

ClientTag users tell this server which client they're really on (Lunar, NoRisk, ... or none),
and look up the players around them, so other ClientTag users only see their real tag.

It's one file with no dependencies and keeps everything in memory. Node 18 or newer.

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

## Admin page

`https://clienttags.fluffykiwi.net/admin` shows the live users per client, every User-Agent
seen since startup with its request count, and the last 2000 requests (time, path, status,
duration, User-Agent). ClientTag reports itself as `ClientTag/<version> (Minecraft <version>;
Fabric|Ornithe)`, so anything else stands out. Only User-Agents are logged - no IPs, names or
UUIDs - and it's all in memory, gone on restart.

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
| `POST /v1/sync` (Bearer token) | `{ clients: ["lunar", ...], lookup: [uuid, ...] }` (at most 512) | `{ users: { uuid: ["lunar"] } }` |
| `POST /v1/leave` (Bearer token) | `{}` | `{}` |
| `GET /health` | | `{ ok: true }` |

A sync refreshes your own presence for 90 seconds. The mod syncs at most every 5 seconds and at
least every 30 while you're on a server, and leaves when you disconnect. Only live users are ever
returned, and nothing is written to disk.
