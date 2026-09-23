# Security Policy

## Reporting a vulnerability

SimTether forwards SMS (including OTPs) between phones — a flaw here is
sensitive by definition. Please report privately to the contact in
`PRIVACY.md` and do not open a public issue until we've had a chance to
respond. We aim to acknowledge within 72 hours.

## Verifying a build

Both apps are signed with the same upload key. The certificate
SHA-256 fingerprint is:

```
B4:05:CF:6E:A2:90:3D:57:E4:18:1F:B0:0B:16:7E:09:
DD:DC:01:A4:4C:A6:93:50:98:64:CA:BA:07:9C:1B:E4
```

Verify a downloaded APK before installing:

```
apksigner verify --print-certs app.apk
# or: keytool -printcert -jarfile app.apk
```

A build whose fingerprint differs is **not ours** — treat it as
hostile. Android also refuses to install a differently-signed APK over
ours, so a successful install-over is itself a weak positive signal.

## What the bridge will tell you

The bridge home screen shows the fingerprint of the **paired client
key** under the QR card, and the client's Settings screen shows **this
phone's own identity fingerprint**. Compare the two: a match means your
phone holds the pin; a mismatch means an unrecognized device paired.
Re-pair rotates the bridge identity and revokes every prior credential.

## Design notes

- Transport: Noise IK (X25519 + ChaCha20-Poly1305) — every frame is
  ciphertext, including on the LAN.
- Pairing token rotates on each session; the client's identity key is
  pinned TOFU on the bridge.
- The optional relay forwards ciphertext only and cannot read payload;
  room ownership is proven cryptographically (`shared/RelayProof.kt`).
- Secrets at rest are Keystore-backed AES/GCM; the bridge warns
  visibly if AndroidKeyStore is unavailable on the device.
