pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central, tried first because the main host rate-limits shared networks
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central, tried first because the main host rate-limits shared networks
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}
rootProject.name = "AIReader"
include(":app")
