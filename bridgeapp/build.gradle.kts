import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Upload-key credentials live in keystore.properties (gitignored) —
// same key as the client app so both listings share Play App Signing.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.simtether.bridgeapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.simtether.bridge"
        minSdk = 26
        targetSdk = 35
        // Versioned in gradle.properties — shared with the client app.
        versionCode = (project.property("simtether.versionCode") as String).toInt()
        versionName = project.property("simtether.versionName") as String
    }

    // Two distribution builds, same applicationId + signing key so the
    // rooted APK upgrades cleanly over the store one on the same phone:
    //   store  — Google Play: everything the policies allow
    //   rooted — GitHub only: adds capabilities Android keeps from
    //            unrooted apps (live GSM call audio). Feature-gated by
    //            BuildConfig.DIST_ROOTED — the extra code paths require
    //            root at runtime, not just this build flag.
    // versionCode MUST stay in defaultConfig — a per-flavor override
    // breaks the Play→GitHub cross-upgrade (a lower rooted versionCode
    // on an upgrade is an install error, not a downgrade warning).
    flavorDimensions += "dist"
    productFlavors {
        create("store") {
            dimension = "dist"
            buildConfigField("boolean", "DIST_ROOTED", "false")
            resValue("string", "app_name", "SimTether Bridge")
        }
        create("rooted") {
            dimension = "dist"
            buildConfigField("boolean", "DIST_ROOTED", "true")
            resValue("string", "app_name", "SimTether Bridge (rooted)")
            versionNameSuffix = "-rooted"
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
            storeFile = keystoreProps.getProperty("storeFile")
                ?.let { rootProject.file(it) }
            storePassword = keystoreProps.getProperty("storePassword")
        }
    }

    buildTypes {
        release {
            if (keystoreProps.getProperty("keyAlias") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // No :client — the sideloaded bridge APK must not carry client
    // code (the paid app). Generic screens come via :ui's UiBackend.
    implementation(project(":shared"))
    implementation(project(":bridge"))
    implementation(project(":ui"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // QR render for the pairing code (encoding only — no scanner here)
    implementation("com.google.zxing:core:3.5.3")
}
