plugins {
    id("com.android.application")
}

android {
    namespace = "com.luoxue.tv"
    // GeckoView 158 要求 compileSdk >= 37.2
    compileSdkVersion("android-37.2")

    defaultConfig {
        applicationId = "com.luoxue.tv"
        minSdk = 26
        targetSdk = 37
        versionCode = 54
        versionName = "1.39"
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

dependencies {
    // GeckoView（Mozilla Firefox 158 内核，2026-10-21 版）：电视端进站主内核，
    // 支持 :has() 等全部现代特性；无签名/下载限制，armeabi-v7a 官方变体
    // v1.32: X5 已整体清除（Chromium 89 能力不足且占用体积）
    implementation("org.mozilla.geckoview:geckoview-omni-armeabi-v7a:158.0.20261021001945")
}
