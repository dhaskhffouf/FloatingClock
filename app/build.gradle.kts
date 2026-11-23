import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ghi đè qua -PghOwner=... -PghRepo=... -PappVersion=... -PappCode=...
fun prop(name: String, def: String): String =
    providers.gradleProperty(name).orNull?.trim()?.takeIf { it.isNotEmpty() } ?: def

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

fun secretOrProp(envKey: String, propKey: String = envKey): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(propKey)?.trim()?.takeIf { it.isNotEmpty() }

val resolvedKeystorePath: String? =
    secretOrProp("KEYSTORE_PATH", "keystore.path")
        ?: rootProject.file("release.jks").takeIf { it.exists() }?.absolutePath

val resolvedStorePassword: String? = secretOrProp("KEYSTORE_PASSWORD", "keystore.password")
val resolvedKeyAlias: String? = secretOrProp("KEY_ALIAS", "key.alias")
val resolvedKeyPassword: String? = secretOrProp("KEY_PASSWORD", "key.password")

val hasReleaseSigning = resolvedKeystorePath != null &&
        !resolvedStorePassword.isNullOrBlank() &&
        !resolvedKeyAlias.isNullOrBlank() &&
        !resolvedKeyPassword.isNullOrBlank()

val parsedVersionCode = prop("appCode", "1").toIntOrNull()?.coerceAtLeast(1) ?: 1
val parsedVersionName = prop("appVersion", "1.0.0")
val ghOwner = prop("ghOwner", "dhaskhffouf")
val ghRepo = prop("ghRepo", "FloatingClock")

android {
    namespace = "com.minhthai.floatclock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.minhthai.floatclock"
        minSdk = 26
        targetSdk = 35
        versionCode = parsedVersionCode
        versionName = parsedVersionName

        buildConfigField("String", "GITHUB_OWNER", "\"$ghOwner\"")
        buildConfigField("String", "GITHUB_REPO", "\"$ghRepo\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(resolvedKeystorePath!!)
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
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
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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