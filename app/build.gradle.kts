plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing: kredensial dibaca dari environment (GitHub Secrets), tidak ada yang disimpan di repo.
// File webapp.jks dipulihkan oleh workflow dari secret KEYSTORE_BASE64 dan tidak boleh di-commit.
val ksFile = file("webapp.jks")
val ksPassword: String? = System.getenv("KEYSTORE_PASSWORD")
val ksAlias: String? = System.getenv("KEY_ALIAS")
val ksKeyPassword: String? = System.getenv("KEY_PASSWORD")
val hasSigning = ksFile.exists() && !ksPassword.isNullOrEmpty() && !ksAlias.isNullOrEmpty() && !ksKeyPassword.isNullOrEmpty()

// Di GitHub Actions, build tanpa kunci yang benar akan menghasilkan APK dengan tanda tangan berbeda
// (tidak bisa menimpa aplikasi yang sudah terpasang), jadi gagalkan dengan pesan yang jelas.
if (!hasSigning && System.getenv("GITHUB_ACTIONS") == "true") {
    throw GradleException("Secret signing belum lengkap: isi KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD di Settings > Secrets and variables > Actions.")
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
    if (hasSigning) {
        signingConfigs {
            create("shared") {
                storeFile = ksFile
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }
    // Build lokal tanpa secret memakai kunci debug bawaan agar tetap bisa dibuat dan diuji
    val appSigning = if (hasSigning) signingConfigs.getByName("shared") else signingConfigs.getByName("debug")
    buildTypes {
        getByName("debug") {
            signingConfig = appSigning
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = appSigning
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
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
