plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dg.nightvision"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dg.nightvision"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val cx = "1.3.4"
    implementation("androidx.camera:camera-core:$cx")
    implementation("androidx.camera:camera-camera2:$cx")
    implementation("androidx.camera:camera-lifecycle:$cx")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
}
