# SimTether — Privacy Policy & Posture

Effective date: [DATE]
Contact: [CONTACT-EMAIL]

This document is both our Play Store privacy policy and our technical
privacy posture statement. It describes what the app does with your
data — which is, by design, almost nothing.

## The short version

- SimTether has **no accounts and no analytics**.
- Your SMS and call data travel **between your own two phones**,
  end-to-end encrypted — over your own WiFi/hotspot by default, or
  (if you enable remote access) through a relay that sees only
  ciphertext.
- We, the developers, **never receive your message content or call
  data**. With remote access enabled, our relay handles encrypted
  traffic and unavoidable connection metadata — described below.
- The only third party involved otherwise is Google Play, if you
  subscribe — they handle payment, we only see whether you're entitled.

## What the apps are

SimTether is two apps. **SimTether Bridge** (sideloaded) runs on a
cheap Android phone holding your SIM. **SimTether** (Play Store) runs
on your main phone and receives SMS, call events, and call control
from the bridge — end-to-end encrypted over the local network, or
over the internet via a relay if you opt in.

## What data the app handles — and where it goes

| Data | Where it goes |
|---|---|
| SMS content (received & sent) | Bridge → your main phone, E2E-encrypted, over your LAN — or through the relay as ciphertext if remote access is on. Stored encrypted on both devices. |
| Call events (number, state, timestamps) | Same path — bridge → your phone, encrypted end-to-end. |
| Contact names (to label senders) | Read on-device for display. Never transmitted to us. |
| Bridge telemetry (battery, carrier, signal, app version) | Sent to *your paired phone* so the client can show bridge status. Nowhere else. |
| Pairing data (public key, pairing token) | Exchanged via QR you scan yourself. The token authenticates the encrypted session and rotates every connection — a copied QR stops working after your phone next connects. |
| Subscription status | Handled by Google Play Billing. We see only "entitled / not entitled" — never card numbers or payment details. |

## The encryption, specifically

- Pairing is one QR scan; the bridge's public key is pinned from that
  moment — subsequent connections are authenticated by key, not by IP.
- Sessions use **Noise IK** (X25519 key exchange, ChaCha20-Poly1305
  transport). The WebSocket frames on the LAN are ciphertext.
- The app permits cleartext HTTP at the OS level because the payload
  encryption happens inside the app — the socket itself carries only
  Noise ciphertext.

## What we deliberately do NOT do

- No analytics SDKs, no crash-reporting services, no ad networks,
  no trackers. The app's only dependencies are UI, QR, billing, and
  crypto libraries — none phone home.
- No server-side message storage. The optional relay (below) forwards
  encrypted frames in memory and keeps nothing.
- No account system. There is nothing to log into and nothing to leak.

## Optional remote access (off by default)

If you enable remote access, your bridge phone registers with our
relay server and your main phone connects through it when the phones
aren't on the same network. What this means honestly:

- **Message content stays end-to-end encrypted.** The relay forwards
  Noise ciphertext between your phones and cannot read it. We do not
  store messages, call contents, or the encryption keys.
- **The relay sees connection metadata**: your phones' IP addresses,
  connection times, data volumes, and an opaque room identifier — the
  minimum needed to splice the sockets.
- Remote access is **off unless you turn it on** in the bridge app's
  settings, and the bridge shows a consent screen first.
- You can also point both phones at your **own relay server** instead
  of ours, in which case we see nothing at all.

The hosted relay is a **best-effort service** — we may change,
suspend, or discontinue it at any time, and remote access through our
relay carries no availability guarantee. If the hosted relay stops,
local/hotspot mode and self-hosted relays keep working unchanged; the
app itself never depends on our infrastructure to function.

Leave remote access off and everything stays on your local network.

## Optional call relay (planned, off by default)

If you enable the call-audio relay feature, calls are forwarded at the
**carrier level** to an internet phone service (SIP provider) which
delivers them to the app. This changes the privacy picture, and the
app shows a consent screen before enabling it:

- Your carrier sees the same forwarding metadata it always sees.
- The SIP provider is technically in the audio path — like any phone
  company, it could access call audio and metadata.
- We see only call-routing information needed to deliver the call.
  We do not record or store audio.
- **Your SMS never touches this path.** It remains local-only always.

Leave the relay off and nothing above applies — call control and SMS
remain 100% local either way.

## Permissions, honestly

The two apps split permissions by role. The **bridge** app (SIM phone)
asks for the sensitive set:

| Permission | Used for |
|---|---|
| `RECEIVE_SMS` / `SEND_SMS` / `READ_SMS` | Receive incoming SMS and send replies on your SIM. |
| `READ_PHONE_STATE` / `ANSWER_PHONE_CALLS` / `CALL_PHONE` | See and control GSM calls. |

The **client** app (main phone) needs none of those:

| Permission | Used for |
|---|---|
| `MANAGE_OWN_CALLS` | Render incoming calls in the native system UI. |
| `CAMERA` | Scanning the pairing QR. |

Both apps:

| Permission | Used for |
|---|---|
| `READ_CONTACTS` | Display names instead of bare numbers. |
| `FOREGROUND_SERVICE_*` | Keeping the encrypted link alive. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | So Android doesn't kill the link; one system prompt, your choice. |
| `POST_NOTIFICATIONS` | SMS/call notifications. |

## Data retention & deletion

All data lives on your devices. Deleting a conversation in the app,
or uninstalling, removes it. There is nothing to request deletion of
from us — we hold nothing. (Subscription records are held by Google
Play under their own policy.)

## Children

The app is not directed at children under 13 and collects no data
from anyone.

## Changes

If this policy changes, the effective date above moves and the change
lands in the public repository history — nothing happens silently.

## Legal posture (KVKK/GDPR)

Your message content never reaches us — we are no data controller or
processor for your SMS or calls. If you enable remote access, our
relay processes the connection metadata described above (IPs, timing,
volumes) as a transient transit function — not stored, not shared.
The other exception is the subscription purchase, where Google Play
is the merchant of record. For questions or requests, reach us at
[CONTACT-EMAIL].
