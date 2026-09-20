# Calls on Your Main Phone — How It Works, What It Costs, What We See

This document explains the optional **Calls Tier** in plain language.
It exists because honesty matters here: this feature involves your
carrier, a phone-number provider, and real money. Read this before
turning it on.

*(Turkish version of this text will ship in-app; this is the source copy.)*

## The short version

- The free app already gives you **every SMS** and **full call control**
  on your main phone — no SIM needed in it, ever.
- The Calls Tier adds the missing piece: **hearing and talking** — real
  two-way call audio — on your main phone.
- It works by forwarding calls at the **carrier level** to an internet
  phone service, which delivers them to the app over the internet.
- It can cost money depending on your carrier, and it means a phone
  company sits in the call path. Both are explained below — nothing is
  hidden.

## Why this tier exists at all

Your main phone can't play GSM call audio directly. Android phones ship
with the software needed to act as a Bluetooth headset **disabled** — we
verified this on real hardware (Galaxy S23 Ultra): the code is inside
the phone, but Samsung locks it and only rooting can unlock it. We won't
ask you to root your phone. So for call audio, calls take a different
road: through your carrier's forwarding feature.

## How a call actually travels

```
Someone calls your Turkish number
        ↓
Your carrier (Turkcell / Vodafone / Türk Telekom)
        ↓   forwards the call — this happens in the carrier's
        ↓   network, not on any phone you own
        ↓
An internet phone number (a "DID") operated for this service
        ↓
The internet → your main phone (bridge hotspot, or ANY WiFi)
        ↓
The app rings → you answer → you talk normally
```

Your main phone needs **no SIM**. The call arrives as ordinary internet
traffic — exactly like a WhatsApp call arrives on a phone with no SIM.

## What it costs — honestly, per carrier

Two separate costs exist. Neither is hidden.

### 1. Your carrier's forwarding charge

When your carrier forwards a call, **your Turkish SIM is billed** for
the forwarded leg. How much depends entirely on which carrier you use:

| Carrier | What you pay |
|---|---|
| **Türk Telekom** | Forwarded calls consume your plan's minutes, like a normal outgoing call. On a plan with bundled minutes: **effectively free**. |
| **Vodafone** | Same — billed at your tariff's rate for that call direction, which on bundled plans means **plan minutes, effectively free**. |
| **Turkcell** | **13 TL flat per forwarded call** — charged separately, *not* covered by your plan's minutes. Bundles exist (e.g. 75 TL/month for 100 forwarded calls). The app detects your carrier and warns you before enabling anything. |

### 2. The service's phone number

Calls need an internet phone number to be forwarded to. We operate a
**shared** number for all users — your calls are identified and routed
by forwarding information inside the call, not by giving each user a
separate number. This keeps the per-user cost near zero. If Turkish
carriers strip that routing information (we're verifying this with
providers), a per-user number would be needed and we'd charge a small
monthly fee — you'd be told the exact price before paying anything.

## Our recommended setup: "forward only when unanswered"

By default we configure **conditional forwarding** (`**61*`), not
"forward everything":

1. A call arrives → your bridge phone rings → the app instantly shows
   the call on your main phone. **Free** — no forwarding happened.
2. Answer on the bridge phone (or its speaker/a paired accessory) →
   call never forwards → **zero cost**.
3. Don't answer within ~15 seconds → the carrier forwards the call →
   it rings in the app on your main phone → **only now does a forwarded
   call (and its cost) occur**.

You only ever pay for calls you actually wanted to take on the main
phone. Everything else stays free.

## What works when — the honest table

| Situation | Calls | SMS | Notes |
|---|---|---|---|
| Bridge phone with you (hotspot on) | Full: ring, answer, talk | Live | The normal experience |
| Bridge at home, **powered on**; you on other WiFi | Calls still reach you via forwarding | **Queued** — syncs when you're back in range | SMS never touches our servers by design |
| Bridge at home, **powered off** | Calls **still** reach you — forwarding lives at the carrier | Delayed until bridge powers on | Hotspot obviously unavailable |

The takeaway: the bridge phone can live on a charger in a drawer. Calls
keep working anywhere you have internet. SMS waits until you're back —
that's a deliberate privacy choice, not a limitation we couldn't solve.

## Privacy — who can see what

We refuse to be vague here. With the Calls Tier ON:

- **Your carrier** sees the same metadata it always sees for forwarded
  calls: who called, when, that it forwarded. Nothing new.
- **The phone-number provider** is in the audio path — like any phone
  company, they could technically see call audio and metadata. We choose
  providers and configure routing to minimize this, but we won't pretend
  it doesn't exist. **This is the real tradeoff of this feature.**
- **We (the app developers)** see only what's needed to route the call
  to your phone — the forwarding information in the call setup. We do
  not record, store, or have access to call audio. There is no account
  system holding your call history.
- **Your SMS never goes through this at all.** Messages stay on the
  encrypted link between your two phones. The Calls Tier touches voice
  only.

If "a provider could theoretically hear my calls" is unacceptable to
you: leave the tier off. You lose nothing else — SMS, notifications,
and call control remain 100% local and private. Answer calls on the
bridge phone's speaker or any Bluetooth calling watch/earpiece.

## What stays free forever

- Pairing, SMS receive/reply, notifications — no servers, no fees.
- Incoming call alerts and full call control on the main phone.
- Answering on the bridge speaker / Bluetooth accessories.
- Everything in "LAN mode."

The Calls Tier is **opt-in**, off by default, and can be turned off
anytime (we'll also show you how to remove the forwarding setting on
your SIM — it's a standard carrier feature, USSD `##61#`).

## Before enabling, the app will tell you

1. Your detected carrier and its forwarding price.
2. Whether conditional (recommended) or full forwarding will be set.
3. That a phone-number provider sits in the call path.
4. Exactly how to undo it.

Nothing is configured until you confirm.
