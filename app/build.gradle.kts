plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "io.bosonnetwork.identity"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.bosonnetwork.identity"
        // Android 11: per-use Keystore authentication (setUserAuthenticationParameters) and a
        // CryptoObject that accepts the screen lock as well as biometrics both start here.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing material is supplied out of band (CI secrets or ~/.gradle properties), never
    // committed. When absent, assembleRelease still builds an unsigned APK.
    val releaseStoreFile = (project.findProperty("IDENTITY_KEYSTORE_FILE") as String?)
        ?: System.getenv("IDENTITY_KEYSTORE_FILE")

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = file(releaseStoreFile)
                storePassword = (project.findProperty("IDENTITY_KEYSTORE_PASSWORD") as String?)
                    ?: System.getenv("IDENTITY_KEYSTORE_PASSWORD")
                keyAlias = (project.findProperty("IDENTITY_KEY_ALIAS") as String?)
                    ?: System.getenv("IDENTITY_KEY_ALIAS")
                keyPassword = (project.findProperty("IDENTITY_KEY_PASSWORD") as String?)
                    ?: System.getenv("IDENTITY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseStoreFile != null) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required: the app dexes the JVM Boson stack (Vert.x 5 / Netty / Jackson).
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:vault"))
    implementation(project(":core:boson"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:identity"))
    implementation(project(":feature:account"))
    implementation(project(":feature:devices"))
    implementation(project(":feature:security"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
}
