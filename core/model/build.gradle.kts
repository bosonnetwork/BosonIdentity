plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.bosonnetwork.identity.core.model"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
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
    testImplementation(libs.junit)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
