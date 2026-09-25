# SimTether Trust Model

Baseline for security/audit passes. Every finding is judged against the
claims below — a violation of a claim is a bug; a missing claim is a
design gap to decide on explicitly.

## Roles

- **Bridge** — the SIM phone (`com.simtether.bridge`). Holds the SIM,
  forwards SMS + GSM call control to the client. Often unattended.
- **Client** — the primary phone (`com.simtether`). The controller;
  holds no SIM duties.
- **Relay** — hosted splice (`simtether-relay.fly.dev`). Optional
  rendezvous when LAN/hotspot isn't available.
- **Bridge owner** — the human holding the SIM phone. May differ from
  the client holder (shared/family SIM, employee device).

## Assets

| Asset | Held by | Exposure if lost |
|---|---|---|
| SIM SMS content | bridge → client | private messages readable |
| Call control / audio | bridge → client | calls answered/made/recorded as owner |
| Bridge identity key (X25519 static) | bridge | impersonation of bridge; silent re-pair |
| Client identity key | client | impersonation of paired client |
| Pairing token (rotates per session) | both | one-slot session hijack |
| Relay token + relay secret | both | relay room access, connect tickets |
| Pairing QR | shown on bridge | full bearer credential — anyone who scans it pairs |

## Adversaries

1. **LAN attacker** — same hotspot/LAN, can see and inject TCP.
2. **Relay operator / network observer** — sees relay traffic; must not
   see plaintext or metadata beyond IP/timing/size.
3. **Rogue client** — unpaired device attempting commands.
4. **Rogue bridge** — a fake bridge trying to harvest a client's data
   or capture its identity.
5. **Physical bridge access** — someone with the unlocked phone briefly
   (reads QR, toggles settings).
6. **App-store reviewer / policy** — permissions must match declared
   use; rooted-only code must not ship to Play.

## Security claims

1. **Transport**: client↔bridge is `Noise_IK_25519_ChaChaPoly_SHA256`.
   The client's ephemeral + the bridge's static key in the QR pin the
   bridge; the pairing token pins the client. LAN attacker sees only
   ciphertext.
2. **Pairing**: the QR is a one-slot bearer credential — first scanner
   wins the pin; `rePair()` rotates the identity and revokes the old
   client. The pairing token rotates per session over the encrypted
   channel, so a photographed QR is worthless after first use.
3. **Relay blindness**: the relay sees ciphertext envelopes only —
   never plaintext, phone numbers, or call metadata. Room tickets are
   derived from `relaySecret`, known only to bridge + paired client.
4. **Opt-in gates**: USSD/service codes, call-audio relay, and remote
   (relay) access are off by default and enable-able **only on the
   bridge device** — never by a remote command.
5. **Visibility**: the bridge runs only while its status notification
   can be shown — ongoing, re-posted on dismiss. A muted channel or an
   app-level notification block is enforced, not just surfaced: the
   service flips its own master switch off (fail closed) and the UI
   explains how to restore it. The owner cannot be silently bridged.
6. **Sensitive settings**: relay/token overrides and toggles live only
   on the bridge; the client cannot push configuration.
7. **Keystore**: secrets are AES/GCM-wrapped by Android Keystore when
   available; plaintext fallback is warned loudly in the UI
   (degraded-store state must never be silent).

## Accepted risks (deliberate)

- Relay sees client IP + connection timing/size metadata — inherent to
  any relay; no padding is attempted.
- A QR shown on screen is a live credential for its 60s display window;
  anyone in camera range during it can pair. Owner-facing UX accepts
  this; re-pair revokes.
- On Android <14 the FGS notification is truly non-dismissable; on 14+
  dismissal is instantly re-posted — a determined owner can still
  force-stop or uninstall (that's correct: it's their device).
- USSD `MODEM_ERR` class failures depend on carrier support — surfaced
  to the user, not hidden.

## Out of scope

- SIM-level attacks (carrier-side interception, SS7, SIM swap).
- Rooted-device malware beyond our own rooted flavor — Magisk grants
  are the owner's responsibility.
- Forensic extraction from a device the attacker fully owns.
