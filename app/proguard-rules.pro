# CameraX
-keep class androidx.camera.core.CameraXConfig { *; }
-keep class androidx.camera.camera2.Camera2Config { *; }
-keepclassmembers class androidx.camera.** {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}

# ViewModel
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>();
    <init>(android.app.Application);
}

# ExifInterface
-keep class androidx.exifinterface.media.ExifInterface { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# V9: ML Kit Segmentation
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-keepclassmembers class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Accompanist
-keep class com.google.accompanist.permissions.** { *; }

# BitmapHandle
-keepnames class com.example.ardrawing.data.BitmapHandle
-keepclassmembers class com.example.ardrawing.data.ImageProcessor {
    int calculateInSampleSize(int, int, int, int);
}

# Suppress notes
-dontnote kotlin.**
-dontnote kotlinx.**
-dontnote androidx.compose.**
-dontnote com.google.**
