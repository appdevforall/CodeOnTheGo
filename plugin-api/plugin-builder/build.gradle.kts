plugins {
    `kotlin-dsl`
}

group = "com.itsaky.androidide.plugins"
version = "1.0.0"

dependencies {
    implementation("com.android.tools.build:gradle:8.8.2")
}

gradlePlugin {
    plugins {
        create("pluginBuilder") {
            id = "com.itsaky.androidide.plugins.build"
            implementationClass = "com.itsaky.androidide.plugins.build.PluginBuilder"
            displayName = "CodeOnTheGo Plugin Builder"
            description = "Gradle plugin for building CodeOnTheGo plugins"
        }
    }
}