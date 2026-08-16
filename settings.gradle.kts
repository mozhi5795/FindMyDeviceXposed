pluginManagement {
    repositories {
        // 国内镜像（优先）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 官方源（备选）
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像（优先）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        // 官方源
        google()
        mavenCentral()
        // LSPosed API
        maven { url = uri("https://api.xposed.info") }
    }
}

rootProject.name = "FindMyDeviceXposed"
include(":app")