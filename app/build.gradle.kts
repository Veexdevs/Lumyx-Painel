plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.veexdevs.lumyxpainel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.veexdevs.lumyxpainel"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val keystorePath = System.getenv("KEYSTORE_PATH")
    if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "lumyx123456"
                keyAlias = System.getenv("KEY_ALIAS") ?: "lumyx"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "lumyx123456"
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ""
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
