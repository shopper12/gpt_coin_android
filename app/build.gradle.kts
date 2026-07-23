plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val ciKeystoreFile = System.getenv("CI_KEYSTORE_FILE")
val ciKeystorePassword = System.getenv("CI_KEYSTORE_PASSWORD")
val ciKeyAlias = System.getenv("CI_KEY_ALIAS")
val ciKeyPassword = System.getenv("CI_KEY_PASSWORD")
val ciVersionCode = System.getenv("CI_VERSION_CODE")?.toIntOrNull() ?: 1
val ciVersionName = System.getenv("CI_VERSION_NAME") ?: "1.0"
val hasCiSigning = !ciKeystoreFile.isNullOrBlank() &&
    !ciKeystorePassword.isNullOrBlank() &&
    !ciKeyAlias.isNullOrBlank() &&
    !ciKeyPassword.isNullOrBlank()

android {
    namespace = "com.cryptotradecoach"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cryptotradecoach"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    signingConfigs {
        if (hasCiSigning) {
            create("ciRelease") {
                storeFile = file(ciKeystoreFile!!)
                storePassword = ciKeystorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Debug APKs use a different package so they never block installation
            // of the persistent-key signed phone release APK.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (hasCiSigning) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
