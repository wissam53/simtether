# SimTether

Your SIM stays home. You don't have to.

SimTether turns a cheap Android phone into a bridge for a registered SIM —
SMS and GSM call control arrive on your main phone, end-to-end encrypted,
with no account and no server in the middle.

Built for situations like IMEI-registration regimes, where a foreign phone
loses cellular service after a grace period: leave the registered SIM — and
its registered phone — at home, and carry whatever phone you want.

## The two apps

| App | Runs on | Distribution |
|---|---|---|
| **SimTether Bridge** (`bridgeapp`) | The Android phone holding your SIM | `store` flavor on Google Play; `rooted` flavor on GitHub Releases |
| **SimTether** (`app`) | Your main phone | Google Play |

The bridge is a background appliance: plug it in, put it on WiFi or its own
hotspot, scan one QR from your main phone, and forget it exists.

## Architecture

```
Bridge phone (SIM)                        Main phone
─────────────────────                     ─────────────────────
SmsManager / Telecom (InCallService)      PhoneAccount + ConnectionService
Foreground service + WS server  ◀──────▶  WS client, persistent conn
        └── optional ciphertext-only relay (wss) ──┘
```

- **Modules:** `:shared` (protocol/crypto/stores), `:bridge` (bridge runtime),
  `:bridgeapp` (bridge APK), `:client` (client runtime), `:ui` (shared
  Compose screens), `:uiclient` (client-only screens), `:app` (client APK),
  `:relay` (JVM splice relay), `site/` (product site).
- **Transport:** Noise IK (X25519 + ChaCha20-Poly1305) over WebSocket.
  LAN/hotspot by default; an opt-in TLS relay covers remote access.
- **Pairing:** one QR scan → bridge key pinned + one-time token. The client
  holds a persistent X25519 identity, pinned TOFU-style on the bridge; the
  pairing token rotates on every connection.
- **Relay:** byte-splicing only — never parses application frames. Room
  ownership is proven by a DH-challenge/HMAC (`RelayProof`), not the access
  token. See `relay/` for the self-contained server (`fly deploy`, or any
  JRE 17 + TLS terminator).
- **Scope:** carrier SMS and GSM calls only. RCS "chat" messages are
  delivered over IP inside the messaging app and never touch the SMS
  pipeline — there is no Android API for third-party RCS access. Chat
  features must be disabled on the bridge phone (Google Messages →
  Messages settings → RCS chats → off) or inbound chats won't be relayed.

## Building

```bash
./gradlew assembleDebug          # everything
./gradlew test                   # JVM unit tests (shared, bridge, relay)
./gradlew :relay:fatJar          # standalone relay jar
```

Bridge flavors:

```bash
./gradlew :bridgeapp:assembleStoreRelease    # Play build
./gradlew :bridgeapp:assembleRootedRelease   # GitHub build (root features)
```

Release signing reads `keystore.properties` (gitignored); the hosted-relay
token reads `relay.properties` (gitignored). Missing either → unsigned/no
default relay, still buildable.

## Status

Working in JVM tests: pairing, encrypted session, SMS forwarding, call
events/control, token rotation, relay registration proof + byte splice,
reconnect/flood handling. On-device (S23 Ultra client + Redmi Note 8
bridge): pairing, mDNS discovery, LAN session, call RINGING→control→
state transitions, SMS send pipeline + carrier-failure reporting, and
relay registration all verified live. Call audio is the rooted-flavor
research track — experimental, GitHub Releases only.

## Privacy

`PRIVACY.md` — no accounts, no analytics, ciphertext-only relay. The site
mirrors it at `site/privacy.html`.

## License

GPLv3 — see [LICENSE](LICENSE). Derivatives must stay open source;
the relay and protocol are free to self-host and audit.
