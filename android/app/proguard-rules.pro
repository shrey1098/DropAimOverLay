# NanoHTTPD uses reflection for its handlers
-keep class fi.iki.elonen.** { *; }
# Media3/ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
