import java.util.Properties

plugins {
    id("com.android.application") version "9.4.1"
}

/**
 * Release signing, read from a gitignored keystore.properties so the key never
 * enters the repository. A build without that file still compiles: it falls back
 * to the debug key, and the APK says so in the release notes rather than
 * silently shipping something that cannot be updated.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "caddy.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "caddy.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                // Keeps `assembleRelease` usable on a fresh clone, but the result
                // is not a distributable: it cannot be updated by a properly
                // signed build.
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures { buildConfig = true }

    defaultConfig {
        // Where the app looks for updates. Sideloading is the only channel, so
        // without this users are stranded on their first APK.
        buildConfigField(
            "String",
            "UPDATE_URL",
            "\"https://github.com/rlatksk/caddy/releases/latest/download/versions.json\"",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(21) }

}

dependencies {
    implementation(project(":core"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // In-process BitTorrent. Minerva is torrent-only, which is 76 of the 80
    // systems a repo can declare; there is no qBittorrent on Android to drive.
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
}
