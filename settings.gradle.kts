pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) // 强制使用此处的仓库配置
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "spw-control-plugin"