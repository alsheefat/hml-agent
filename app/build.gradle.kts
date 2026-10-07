plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Auto-update support -------------------------------------------------------------
// GitHub Actions provides these. Every CI build gets a higher versionCode than the last, so
// Android accepts it as an in-place update; the app compares BUILD_NUMBER with the newest
// GitHub Release (tag "build-<n>") to know when to offer an update.
val ciBuildNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0
val ciRepository: String = System.getenv("GITHUB_REPOSITORY") ?: ""

android {
    namespace = "com.hmlai.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hmlai.agent"
        minSdk = 26
        targetSdk = 34
        versionCode = 100 + ciBuildNumber
        versionName = "1.5.$ciBuildNumber"
        buildConfigField("int", "BUILD_NUMBER", "$ciBuildNumber")
        buildConfigField("String", "UPDATE_REPO", "\"$ciRepository\"")
    }


    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}


dependencies {
    // --- from stage 3 (device-command capability) ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // --- new for stage 4 (UI: drawer, attachments, login) ---
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.facebook.android:facebook-login:17.0.1")
}
