plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.farmerboy.silageloads"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.farmerboy.silageloads"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Debug signing so `assembleRelease` produces an installable APK without
            // a keystore. Swap in your own signingConfig before any store upload.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // OpenStreetMap: no API key and no Google Maps account needed.
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    // Renders downloaded per-state offline map files (vector, full detail).
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.18")
    // Accounts + shared jobs. Dormant until google-services.json is added.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
}

// Sharing goes live only when the Firebase config file exists; without it the
// app builds fine and the crew features show as "needs setup".
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
