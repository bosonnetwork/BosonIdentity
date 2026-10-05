pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The Boson client libraries are released on Maven Central; mavenLocal stays in the list
        // so an unreleased snapshot can be tested by installing it with `mvn clean install`.
        mavenLocal()
    }
}

rootProject.name = "BosonIdentity"

include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:vault")
include(":core:boson")
include(":core:qr")
include(":feature:onboarding")
include(":feature:identity")
include(":feature:account")
include(":feature:devices")
