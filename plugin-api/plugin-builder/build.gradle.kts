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
            displayName = "Code on the Go Plugin Builder"
            description = "Gradle plugin for building Code on the Go plugins"
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }
}
