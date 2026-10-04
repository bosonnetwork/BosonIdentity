plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.bosonnetwork.identity.core.vault"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The Boson stack (Vert.x 5, Netty, Jackson) uses Java 11+ APIs; desugaring backfills them.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core:model"))

    // Ed25519 keys, ids and Base58 come from Boson's own API, so formats stay byte-compatible with
    // the Director, Photon and boson-cli. Logging goes through slf4j; the desktop binding is excluded.
    api(libs.boson.api) {
        exclude(group = "ch.qos.logback")
    }
    // BiometricPrompt with a CryptoObject unlocks the Keystore keys that wrap the identity.
    api(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android's org.json is a stub on the JVM; the real one lets the vault file be tested there.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
