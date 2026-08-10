// AGP 8.5.0 yêu cầu Android Studio Koala+ (2024.1.1)
// Tuy nhiên GitHub Actions không cần Android Studio để build
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}
