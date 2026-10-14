plugins {
    `kotlin-dsl`
}

dependencies {
    // Supplied at run time by the Android Gradle plugin the build already applies.
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
    compileOnly("org.ow2.asm:asm:9.7")
}

gradlePlugin {
    plugins {
        register("vertxAndroidFix") {
            id = "io.bosonnetwork.vertx-android-fix"
            implementationClass = "io.bosonnetwork.gradle.VertxAndroidFixPlugin"
        }
    }
}
