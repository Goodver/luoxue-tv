plugins {
    id("com.android.application")
}

android {
    namespace = "com.luoxue.tv.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.luoxue.tv.mobile"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.5"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../../keystore/luoxue-release.jks")
            storePassword = "luoxue2026"
            keyAlias = "luoxue"
            keyPassword = "luoxue2026"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
