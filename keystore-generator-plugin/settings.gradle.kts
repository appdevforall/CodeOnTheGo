rootProject.name = "keystore-generator-plugin"

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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

include(":plugin-api")
project(":plugin-api").projectDir = file("../plugin-api")