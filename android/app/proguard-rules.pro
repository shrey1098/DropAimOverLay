

-keep class fi.iki.elonen.** { *; }

-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

-keep class com.dropaim.app.UploadWorker { public <init>(...); }
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
}

-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
