// Build logic for this project's own Gradle build: compiled first, then applied by the root
// build script. An included build rather than buildSrc, because its plugin has to see the Android
// Gradle plugin's classes, which buildSrc's class loader cannot.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
