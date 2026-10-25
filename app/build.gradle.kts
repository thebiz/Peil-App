plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.john.peil"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.john.peil"
        minSdk = 26
        targetSdk = 35
        // Sichtbare Version steht in version.txt (z. B. 0.9). Die interne Zählnummer
        // für den Updater erhöht GitHub bei jedem Bau automatisch.
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = rootProject.file("version.txt").readText().trim()

        // ARCore-Schlüssel kommt aus dem GitHub-Secret ARCORE_API_KEY (steht nie im Code)
        manifestPlaceholders["arcoreApiKey"] = System.getenv("ARCORE_API_KEY") ?: ""
    }

    // Fester Schlüssel im Repo, damit sich neue Versionen einfach über die alte installieren lassen.
    // Für den Play Store kommt später ein eigener, geheimer Schlüssel.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.ar:core:1.46.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
