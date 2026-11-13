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
        versionCode = 85
        versionName = "1.45.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
