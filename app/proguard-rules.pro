# ============================================================
# ReadVox ProGuard Rules
# ============================================================

# --- Android基本 ---
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- AndroidManifest に登録したコンポーネント ---
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.preference.PreferenceFragment
-keep public class * extends androidx.preference.PreferenceFragmentCompat

# --- AutoLearnManager: Gson でシリアライズするデータクラス ---
-keep class com.kindletts.reader.ocr.AutoLearnManager$LearnedPattern { *; }
-keep class com.kindletts.reader.ocr.AutoLearnManager$LearnedPattern$** { *; }

# --- BuildConfig ---
-keep class com.kindletts.reader.BuildConfig { *; }

# --- Gemini / Google AI SDK ---
-keep class com.google.ai.client.generativeai.** { *; }
-dontwarn com.google.ai.client.generativeai.**

# --- ML Kit (OCR) ---
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# --- OpenCV ---
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# --- Kuromoji (形態素解析) ---
-keep class com.atilika.kuromoji.** { *; }
-dontwarn com.atilika.kuromoji.**

# --- Gson ---
-keepattributes Signature
-keepattributes Exceptions
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# --- Kotlinx Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# --- WorkManager ---
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# --- Security Crypto (EncryptedSharedPreferences) ---
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# --- Enum クラス ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Parcelable ---
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# --- Native メソッド ---
-keepclasseswithmembernames class * {
    native <methods>;
}
