plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.ardrawing"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ardrawing"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.9"
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime-livedata")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    // CameraX
    val cameraVer = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraVer")
    implementation("androidx.camera:camera-camera2:$cameraVer")
    implementation("androidx.camera:camera-lifecycle:$cameraVer")
    implementation("androidx.camera:camera-view:$cameraVer")

    // ExifInterface
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Accompanist
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // =========================================================================
    // V9 MỚI: ML Kit Selfie Segmentation
    // Tự động detect & tách người khỏi nền (on-device, không cần internet)
    // Model được bundled sẵn trong APK qua metadata trong AndroidManifest.xml
    // =========================================================================
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
