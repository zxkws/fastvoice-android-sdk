pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("usePublishedSdk").isPresent) {
            mavenLocal()
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "fastvoice-android-sdk"
include(":fastvoice-sdk")
include(":sample")
