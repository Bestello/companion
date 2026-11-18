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
        // the Aptabase SDK
        maven("https://jitpack.io")
    }
}
rootProject.name = "FMCCompanion"
include(":app")
