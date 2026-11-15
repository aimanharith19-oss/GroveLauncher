plugins {
    id("com.android.application")
}

android {
    namespace = "org.canopy.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.canopy.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 93
        versionName = "1.47.0"
    }

    // Signed with your own key when KEYSTORE_FILE is set (the GitHub workflow does this from repository secrets);
    // otherwise with the debug key, so a plain build still works.
    signingConfigs {
        create("grove") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "canopy"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null) signingConfigs.getByName("grove") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // No dependencies on purpose: framework APIs only, nothing that can phone home.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
