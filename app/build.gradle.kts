plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "cz.knihajizd"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.knihajizd"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNo
        versionName = "0.1.$buildNo"
    }
    // Stálý klíč v repozitáři, aby šla každá nová verze nainstalovat přes starou bez ztráty dat.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
