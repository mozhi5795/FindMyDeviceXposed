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
        // LSPosed API
        maven { url = uri("https://api.xposed.info") }
    }
}

rootProject.name = "FindMyDeviceXposed"
include(":app")