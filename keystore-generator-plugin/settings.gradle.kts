rootProject.name = "keystore-generator-plugin"

pluginManagement {
    includeBuild("../plugin-api/gradle-plugin")
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