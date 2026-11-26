plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.sunmind.mangaoverlay"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sunmind.mangaoverlay"
        minSdk = 26; targetSdk = 34; versionCode = 6; versionName = "3.3"
        ndk { abiFilters += "arm64-v8a" }
    }
    // keystore คงที่ ทำให้ APK จากทุก build ลงทับกันได้ (ไม่ต้องถอนการติดตั้งก่อน)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.opencv:opencv:4.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
