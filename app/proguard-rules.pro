# ProGuard rules for NAS Music TV

# Keep data classes
-keep class com.nasmusic.tv.data.model.** { *; }
-keep class com.nasmusic.tv.data.prefs.** { *; }
-keep class com.nasmusic.tv.backend.** { *; }

# 百度网盘 DTO 类（显式 keep，防御 Gson 类型擦除/R8 收缩——v2.5.1 曾因此崩溃；
# 与上方 backend.** 宽规则冗余但明确，新增序列化模型时勿删）
-keep class com.nasmusic.tv.backend.network.baidu.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ExoPlayer
-keep class androidx.media3.** { *; }

# ZXing (二维码生成)
-keep class com.google.zxing.** { *; }

# NanoHTTPD (本地 HTTP server，实际 Java 包为 fi.iki.elonen)
-keep class fi.iki.elonen.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ONNX Runtime (Spleeter ONNX 人声分离)
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }

# Strip debug logs in release builds (ProGuard removes the entire Log.d/v call,
# including string computation — more efficient than runtime if(BuildConfig.DEBUG) checks)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
