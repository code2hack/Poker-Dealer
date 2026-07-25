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
        google()
        mavenCentral()
    }
}

rootProject.name = "Poker-Dealer"

include(
    ":apps:dealer",
    ":apps:poker",
    ":prototypes:android-hotspot:core",
    ":prototypes:android-hotspot:dealer",
    ":prototypes:android-hotspot:poker",
    ":shared:protocol",
    ":shared:domain",
    ":shared:testing",
)
