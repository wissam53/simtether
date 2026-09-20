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
    // Noise Protocol reference implementation (rweather lineage) —
    // plain-Java crypto fallbacks, so it works on minSdk 26 without
    // provider quirks. Powers the IK handshake in SecureSession.
    implementation("com.github.auties00:noise-java:1.2")
    testImplementation("junit:junit:4.13.2")
}
