plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Có thể ghi đè bằng -PghOwner=... -PghRepo=... -PappVersion=... -PappCode=...
// (GitHub Actions tự truyền các giá trị này, nên OTA tự khớp với repo của bạn)
fun prop(name: String, def: String): String = providers.gradleProperty(name).orNull ?: def

val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    namespace = "com.minhthai.floatclock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.minhthai.floatclock"
        minSdk = 26
        targetSdk = 35
        versionCode = prop("appCode", "1").toInt()
        versionName = prop("appVersion", "1.0.0")

        buildConfigField("String", "GITHUB_OWNER", "\"${prop("ghOwner", "YOUR_GITHUB_USERNAME")}\"")
        buildConfigField("String", "GITHUB_REPO", "\"${prop("ghRepo", "FloatingClock")}\"")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
