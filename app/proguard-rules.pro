# SimTether release rules.

# BouncyCastle is used through direct API calls (X25519Agreement,
# ChaCha20Poly1305, HKDF) — no Provider reflection — but keep the
# classes whole so R8 doesn't strip members the AEAD engine needs.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# kotlinSerialization is compiler-generated — no reflection keeps
# needed. Keep generic signatures so Service/Activity entry points
# survive shrinking cleanly.
-keepattributes Signature, *Annotation*

# Service components are instantiated by the system from the manifest.
-keep public class * extends android.app.Service
-keep public class * extends android.telecom.InCallService
-keep public class * extends android.telecom.ConnectionService
-keep public class * extends android.content.BroadcastReceiver
