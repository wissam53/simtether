# SimTether — Privacy Policy & Posture

Effective date: [DATE]
Contact: [CONTACT-EMAIL]

This document is both our Play Store privacy policy and our technical
privacy posture statement. It describes what the app does with your
data — which is, by design, almost nothing.

## The short version

- SimTether has **no backend servers, no accounts, and no analytics**.
- Your SMS and call data travel **directly between your own two
  phones** over your own WiFi/hotspot, end-to-end encrypted.
- We, the developers, **receive nothing**. There is no server to
  breach and no database holding your messages.
- The only third party involved is Google Play, if you subscribe —
  they handle payment, we only see whether you're entitled.

## What the app is

SimTether is a two-device bridge: a cheap Android phone holding your
SIM ("bridge") forwards SMS and call events to your main phone
("client") over the local network. You pick the role at first launch.

## What data the app handles — and where it goes

| Data | Where it goes |
|---|---|
| SMS content (received & sent) | Bridge → your main phone, E2E-encrypted, over your LAN. Stored locally on both devices. **Never leaves your devices.** |
| Call events (number, state, timestamps) | Same — bridge → your phone over LAN only. |
| Contact names (to label senders) | Read on-device for display. Never transmitted to us. |
| Bridge telemetry (battery, carrier, signal, app version) | Sent to *your paired phone* so the client can show bridge status. Nowhere else. |
| Pairing data (public key, one-time token) | Exchanged via QR you scan yourself. Used to establish the encrypted session and verify identity. |
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
- No server-side message storage or relay. If both your phones are
  off, your SMS waits on the bridge — it does not detour through us.
- No account system. There is nothing to log into and nothing to leak.

## Optional call relay (off by default)

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

The manifest asks for a lot because one APK serves both roles. What
each sensitive permission is actually for:

| Permission | Used for |
|---|---|
| `RECEIVE_SMS` / `SEND_SMS` / `READ_SMS` | Bridge role only: receive incoming SMS and send replies on your SIM. |
| `READ_PHONE_STATE` / `ANSWER_PHONE_CALLS` / `CALL_PHONE` | Bridge role only: see and control GSM calls. |
| `MANAGE_OWN_CALLS` | Client role only: render incoming calls in the native system UI. |
| `READ_CONTACTS` | Both roles: display names instead of bare numbers. |
| `CAMERA` | Client role only: scanning the pairing QR. |
| `FOREGROUND_SERVICE_*` | Keeping the encrypted LAN link alive. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | So Android doesn't kill the relay; one system prompt, your choice. |
| `POST_NOTIFICATIONS` | SMS/call notifications on the main phone. |

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

Because no personal data reaches us, we act as no data controller or
processor for your messages or calls. The one exception is the
subscription purchase, where Google Play is the merchant of record.
For questions or requests, reach us at [CONTACT-EMAIL].
