plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// version.txt (repository root) is the version of every component; the
// version code is derived from it (1.2.3 -> 10203).
val appVersion = rootProject.file("../version.txt").readText().trim()
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// Release signing comes from the environment (CI secrets). A
// stable key is required so installed versions update in place.
val releaseKeystore = System.getenv("ANDROID_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "net.silvara.relaycast"
    compileSdk = 37

    // A prebuilt library without symbols to strip (androidx.graphics): kept
    // as it is, which is what the build did anyway, without the warning.
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
    }

    defaultConfig {
        applicationId = "net.silvara.relaycast"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // On-device translation of synopses (phone).
    implementation(libs.mlkit.translate)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.tv.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    // Phone player: play/pause in picture-in-picture and from headsets.
    implementation(libs.media3.session)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)
    // Phone app: release reminders and QR scanning (Google's scanner, no
    // camera permission). Synopsis translation uses the system's translator
    // app: an on-device model would add tens of MB the TVs also download.
    implementation(libs.work.runtime)
    implementation(libs.code.scanner)
    // QR codes read from an image (the model comes with Play services).
    implementation(libs.mlkit.barcode)
    // Phone and tablet: Google Cast to a Chromecast or Google TV (Default
    // Media Receiver); the device picker is our own on top of MediaRouter.
    implementation(libs.cast.framework)
    // Phone and tablet: window size classes and the fold's posture and hinge.
    implementation(libs.compose.material3.adaptive)
}
