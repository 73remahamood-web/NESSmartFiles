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
        versionCode = 2
        versionName = "0.2.0-alpha"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        create("nesStable") {
            val keystorePath = System.getenv("NES_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("NES_KEYSTORE_PASS")
                keyAlias = System.getenv("NES_KEY_ALIAS") ?: "nes"
                keyPassword = System.getenv("NES_KEY_PASS")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false; isShrinkResources = false
            if (!System.getenv("NES_KEYSTORE_PATH").isNullOrBlank())
                signingConfig = signingConfigs.getByName("nesStable")
        }
        debug {
            isMinifyEnabled = false
            // Keep debug + run-as for v0.1 data migration; stable key makes future APKs updatable.
            if (!System.getenv("NES_KEYSTORE_PATH").isNullOrBlank())
                signingConfig = signingConfigs.getByName("nesStable")
        }
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
