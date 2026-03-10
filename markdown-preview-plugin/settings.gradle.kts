rootProject.name = "markdown-previewer-plugin"

pluginManagement {
    // Point to CodeOnTheGo project's plugin-builder
    includeBuild("/Users/astrocoder/Documents/stuff/Projects/ADFA/CodeOnTheGo/plugin-api/plugin-builder")
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
project(":plugin-api").projectDir = file("/Users/astrocoder/Documents/stuff/Projects/ADFA/CodeOnTheGo/plugin-api")
