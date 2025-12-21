rootProject.name = "apk-viewer-plugin"

pluginManagement {
    includeBuild("../plugin-api/plugin-builder")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

include(":plugin-api")
project(":plugin-api").projectDir = file("../plugin-api")
