# SimTether release rules.

# noise-java picks cipher/DH implementations via JCE lookups and
# falls back to bundled plain-Java versions — keep the tree intact so
# R8 can't strip a fallback the runtime may load by name.
-keep class com.southernstorm.noise.** { *; }
-dontwarn com.southernstorm.noise.**

# kotlinSerialization is compiler-generated — no reflection keeps
# needed. Keep generic signatures so Service/Activity entry points
# survive shrinking cleanly.
-keepattributes Signature, *Annotation*

# Service components are instantiated by the system from the manifest.
-keep public class * extends android.app.Service
-keep public class * extends android.telecom.InCallService
-keep public class * extends android.telecom.ConnectionService
-keep public class * extends android.content.BroadcastReceiver

# Correspondent numbers flow through Log.d in release builds —
# strip d/v (keep i/w/e) so device seizure doesn't expose metadata.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
