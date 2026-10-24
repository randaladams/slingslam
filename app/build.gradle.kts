plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI sets GITHUB_RUN_NUMBER so every build gets a higher version and installs over the last one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.adamselite.slingslam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adamselite.slingslam"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Release signing uses the private upload key. CI writes it from the repository secrets
    // (SIGNING_KEYSTORE_BASE64 etc.) to app/release.jks; it is never committed.
    // Without those variables (a local build) the release falls back to the standard debug key.
    val ksFile = file("release.jks")
    val hasReleaseKey = ksFile.exists() && System.getenv("SIGNING_STORE_PASSWORD") != null
    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = ksFile
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}
