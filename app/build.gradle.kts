plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.sunmind.mangaoverlay"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sunmind.mangaoverlay"
        minSdk = 26; targetSdk = 34; versionCode = 8; versionName = "3.5"
        ndk { abiFilters += "arm64-v8a" }
    }
    // keystore คงที่ ทำให้ APK จากทุก build ลงทับกันได้ (ไม่ต้องถอนการติดตั้งก่อน)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            // แอปที่ debuggable=true ถูก ART รันแบบเปิดโหมดดีบัก (ช้ากว่าและกินแบตกว่าอย่างเห็นได้ชัด)
            // ยังคงใช้ assembleDebug + keystore เดิมทุกอย่าง จึงลงทับเวอร์ชันเก่าได้เหมือนเดิม; ถ้าต้อง attach debugger ให้เปลี่ยนเป็น true
            isDebuggable = false
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions {
        jvmTarget = "17"
        // ตัดการเช็ก null ภายในที่ Kotlin แทรกให้ทุกพารามิเตอร์/การเรียก (ฮอตลูป tracking/OCR เรียกเป็นล้านครั้ง)
        freeCompilerArgs += listOf("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.opencv:opencv:4.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
}
