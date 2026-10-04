// Top-level build file. Plugins are declared (apply false) here and applied per module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// The JVM Boson stack (Vert.x, Netty, Jackson, BouncyCastle) ships overlapping META-INF metadata and
// multi-release / module-info artifacts that Android packaging and D8 cannot merge. Every module that
// packages an APK strips the non-runtime ones: the app, and each module's instrumented-test APK.
val jvmMetadataExcludes = setOf(
    "META-INF/INDEX.LIST",
    "META-INF/DEPENDENCIES",
    "META-INF/*.kotlin_module",
    "META-INF/LICENSE",
    "META-INF/LICENSE.txt",
    "META-INF/LICENSE.md",
    "META-INF/NOTICE",
    "META-INF/NOTICE.txt",
    "META-INF/NOTICE.md",
    "META-INF/DISCLAIMER",
    "META-INF/*.SF",
    "META-INF/*.DSA",
    "META-INF/*.RSA",
    "META-INF/versions/**",
    "META-INF/io.netty.versions.properties",
    "META-INF/native-image/**",
    "**/module-info.class",
)

subprojects {
    listOf("com.android.application", "com.android.library").forEach { plugin ->
        pluginManager.withPlugin(plugin) {
            extensions.configure<com.android.build.api.dsl.CommonExtension<*, *, *, *, *, *>>("android") {
                packaging.resources.excludes += jvmMetadataExcludes
            }
        }
    }
}
