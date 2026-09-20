plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.simtether.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // api — consumers call Protocol.json/payloadAs, so serialization
    // must be on their compile classpath too.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Low-level crypto API (X25519/HKDF/ChaCha20-Poly1305) — no provider
    // registration, works on all API levels including minSdk 26.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
