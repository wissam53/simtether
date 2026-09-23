plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.simtether.bridge"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        // JVM unit tests touch android.util.Log — return defaults
        // instead of "not mocked" crashes.
        unitTests.isReturnDefaultValues = true
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
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.15.0")
    // api — BridgeService extends LifecycleService; app module touches it
    api("androidx.lifecycle:lifecycle-service:2.8.7")
    // WebSocket server for the hotspot LAN channel
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    testImplementation("junit:junit:4.13.2")
    // RelayLink tests drive a real RelayServer on loopback — the
    // registration-proof handshake only gets coverage if both halves
    // run their production code against each other.
    testImplementation(project(":relay"))
}
