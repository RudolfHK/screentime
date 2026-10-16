pluginManagement {
    repositories {
        google()
        // Google-hosted mirror of Maven Central (avoids Central rate limits on shared CI IPs).
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}

rootProject.name = "ScreenTimeDiagnostics"
include(":app", ":core")
