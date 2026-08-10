-keep class androidx.camera.core.CameraXConfig { *; }
-keep class androidx.camera.camera2.Camera2Config { *; }
-keepclassmembers class androidx.camera.** {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>();
    <init>(android.app.Application);
}
-keep class androidx.exifinterface.media.ExifInterface { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
-keep class com.google.accompanist.permissions.** { *; }
-keepnames class com.example.ardrawing.data.BitmapHandle
-keepclassmembers class com.example.ardrawing.data.ImageProcessor {
    int calculateInSampleSize(int, int, int, int);
}
-dontnote kotlin.**
-dontnote kotlinx.**
-dontnote androidx.compose.**
