plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.bosonnetwork.identity.core.boson"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
    implementation(project(":core:vault"))

    // All Director communication: user, device and admin clients, the guest client, OAuth.
    api(libs.boson.director.client) {
        exclude(group = "ch.qos.logback")
    }
    // The web gateway (Higgs): publishes the profile card to the DHT through the home node.
    implementation(libs.boson.higgs) {
        exclude(group = "ch.qos.logback")
    }
    // slf4j 2.x binding for Android (writes to System.err, which logcat picks up).
    runtimeOnly(libs.slf4j.simple)

    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    // The identity in use is remembered in a Preferences DataStore, which ActiveIdentity takes.
    api(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android's org.json is a stub on the JVM; the real one lets a real vault be used in tests.
    testImplementation(libs.json)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
