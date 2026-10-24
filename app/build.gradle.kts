plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI sets GITHUB_RUN_NUMBER so every build gets a higher version and installs over the last one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.adamselite.slingslam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.adamselite.slingslam"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        // AdMob: the Play Store bundle (built with -PplayStore) uses the real ids; the side-loaded
        // APK always uses Google's public test ids, so a developer phone never loads a real ad.
        val realAds = project.hasProperty("playStore")
        manifestPlaceholders["admobAppId"] =
            if (realAds) "ca-app-pub-7909301840077799~8581089712" else "ca-app-pub-3940256099942544~3347511713"
        buildConfigField("String", "ADMOB_BANNER",
            if (realAds) "\"ca-app-pub-7909301840077799/2215740344\"" else "\"ca-app-pub-3940256099942544/6300978111\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL",
            if (realAds) "\"ca-app-pub-7909301840077799/7268008049\"" else "\"ca-app-pub-3940256099942544/1033173712\"")
        buildConfigField("String", "ADMOB_REWARDED",
            if (realAds) "\"ca-app-pub-7909301840077799/2335867685\"" else "\"ca-app-pub-3940256099942544/5224354917\"")
    }

    buildFeatures {
        buildConfig = true
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

dependencies {
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
}
