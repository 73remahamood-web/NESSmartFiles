plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}
android {
    namespace = "ye.nes.smartfiles"
    compileSdk = 36
    defaultConfig {
        applicationId = "ye.nes.smartfiles"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release { isMinifyEnabled = false; isShrinkResources = false }
        debug { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
    implementation(project(":aichat"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
