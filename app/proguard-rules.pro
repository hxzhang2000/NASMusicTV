# ProGuard rules for NAS Music TV

# Keep data classes
-keep class com.nasmusic.tv.data.model.** { *; }
-keep class com.nasmusic.tv.backend.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ExoPlayer
-keep class androidx.media3.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Strip debug logs in release builds (ProGuard removes the entire Log.d/v call,
# including string computation — more efficient than runtime if(BuildConfig.DEBUG) checks)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
