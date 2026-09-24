# SimTether relay — self-hosting

A single small JVM service that splices two WebSockets byte-for-byte so
a bridge and client can reach each other across the internet. It sees
**ciphertext only** — the Noise IK session runs end-to-end through it —
and stores nothing.

```
bridge → wss://your-host/register/{roomId}   (DH+HMAC ownership proof)
client → wss://your-host/connect/{roomId}    (room ticket required)
```

## Run it

```bash
./gradlew :relay:fatJar          # → relay/build/libs/simtether-relay.jar
ACCESS_TOKEN=change-me java -jar relay/build/libs/simtether-relay.jar
```

Or Docker (build the jar first — the Dockerfile just copies it):

```bash
docker build -t simtether-relay relay/
docker run -p 44711:44711 -e ACCESS_TOKEN=change-me simtether-relay
```

For the hosted instance on Fly.io, `deploy.sh` does the whole thing:
fatJar → `fly deploy` → `ACCESS_TOKENS` sync from `../relay.properties`.

## Configuration

| Env var | Required | Meaning |
|---|---|---|
| `ACCESS_TOKENS` | yes | Comma-separated accepted tokens. Rotation: add the new token, deploy, then remove the old one. |
| `ACCESS_TOKEN` | — | Single-token fallback (same purpose). |
| `PORT` | no | Listen port, default `44711`. |
| `TRUST_PROXY_HEADERS` | **careful** | Set `1` **only** behind a trusted edge that injects `Fly-Client-IP`/`X-Forwarded-For` (e.g. Fly.io). Per-IP caps then key on the real client IP. On a direct-facing deployment these headers are attacker-controlled — leave it unset so the socket address stays authoritative. |

The server refuses to start with no tokens — an open relay is a free
anonymous byte pipe.

## TLS

The jar speaks plain `ws://` — terminate TLS in front of it (Caddy,
nginx, traefik, or Fly's edge) so clients get `wss://`. Point both apps
at `wss://your-host:port` (bridge: Settings → Remote access → custom
relay + token; the client learns it from the pairing QR).

## Limits and behavior

- **In-memory rooms — run exactly one instance.** A bridge registered on
  machine A is invisible to a client landing on machine B; there is no
  cross-instance lookup. Restart drops all registrations (bridges
  re-register automatically within seconds).
- Pre-auth / proof timeouts: 10s. Socket liveness: 60s ping watchdog.
- Caps: 64 conns per IP, 2048 total, 20 registrations/min/IP,
  32 pending registrations global.
- Data plane: 128 KB/min per splice (raised to 2 MB/min while a room is
  in bridge-authorized media mode, max 2h).
- Client slots are non-evictable — a second `/connect` gets `409` while
  one is attached.

## What it logs

Lifecycle only: registrations, splices, detaches, and logged rejection
reasons (`bad token`, `bad path`, `reg rate`, `conn cap`, proof
failures). Connect-path rejections (`bad ticket`, `no bridge`,
`room occupied`) refuse the HTTP upgrade without a log line. Never
payloads, tokens, or keys — there is nothing sensitive to leak.
