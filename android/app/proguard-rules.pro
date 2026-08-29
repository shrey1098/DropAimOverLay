# R8 rules for the release build.
#
# R8 renames and removes anything it cannot prove is reachable. Three kinds of
# thing in this app are reached by NAME rather than by a call it can see, and
# would break at runtime while compiling perfectly:
#
#   * Workers      WorkManager instantiates them from a class name string.
#   * Framework    Reflection onto android.* is safe (R8 never renames the
#                  platform), so BluetoothLink's createRfcommSocket lookup needs
#                  no rule — it is listed here only so the next person does not
#                  go looking for one.
#   * Libraries    NanoHTTPD and Media3 do their own reflection internally.
#
# Everything else — Config, Settings, MavlinkService, VideoPipe, the licence and
# the aim plumbing — is renamed, which is the point.

# ── Embedded HTTP/WebSocket server ───────────────────────────────────────────
-keep class fi.iki.elonen.** { *; }

# ── Media3 / ExoPlayer ───────────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ── WorkManager: UploadWorker is constructed reflectively from its name ──────
# Without this the metrics upload silently stops working in release only.
-keep class com.dropaim.app.UploadWorker { public <init>(...); }
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

# ── Activities, receivers and services named in the manifest are kept by AGP
#    automatically; no rules needed for MainActivity/ActivationActivity/
#    ExportReceiver.

# ── Keep enough to make a crash report readable, without shipping the original
#    file names. Line numbers alone are useless to a reader with no source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin intrinsics produce noisy null-check messages naming parameters; strip
# them so the release binary does not narrate its own API.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
}

# Strip Log.d/v/i from release. Log.w and Log.e stay: a field fault still has to
# be diagnosable from logcat, and those lines do not narrate the algorithm.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
