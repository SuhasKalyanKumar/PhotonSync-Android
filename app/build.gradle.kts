import java.util.Properties

plugins {
    id("com.android.application")
}

val appVersion = "1.3.2"

// Release signing key lives in keystore/ (gitignored). Without it, release builds are left unsigned.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "me.suhaskalyan.photonsync"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.suhaskalyan.photonsync"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = appVersion

        // Vosk ships native code; these two ABIs cover practically every phone and keep the APK small
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += "x86_64" } // for testing on an emulator
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
}

// ./gradlew exportApk -> apk/PhotonSync-<version>.apk, ready to share
tasks.register<Copy>("exportApk") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("apk"))
    rename { "PhotonSync-$appVersion.apk" }
}
