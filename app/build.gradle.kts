import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is optional: contributors/CI without keystore.properties (gitignored,
// not published) still get a working, just unsigned, release build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasSigningConfig = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasSigningConfig) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "dev.camstream.app"
    compileSdk = 34

    // Disabled for F-Droid reproducible builds: this block embeds an encrypted,
    // git-derived VCS fingerprint that differs between build environments (e.g.
    // whether git is available), which breaks byte-for-byte APK reproducibility.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "dev.camstream.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // The real cause of the F-Droid reproducible-build mismatch: AGP embeds a
            // real META-INF/version-control-info.textproto file recording git state,
            // which differs depending on whether/how git is available in the build
            // environment (this is separate from, and not fixed by, dependenciesInfo
            // above). Disabling it removes that environment-dependent byte.
            vcsInfo.include = false

            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
}
