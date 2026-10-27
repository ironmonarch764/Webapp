plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.myweb.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.myweb.app"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }
    signingConfigs {
        create("shared") {
            storeFile = file("webapp.jks")
            storePassword = "webapp123"
            keyAlias = "webapp"
            keyPassword = "webapp123"
        }
    }
    buildTypes {
    getByName("debug") {
        signingConfig = signingConfigs.getByName("shared")
    }
    getByName("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        signingConfig = signingConfigs.getByName("shared")
    }
}
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
