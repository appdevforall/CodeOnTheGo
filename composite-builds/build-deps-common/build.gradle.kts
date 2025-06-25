import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    alias(libs.plugins.kotlin.jvm)
}

allprojects {
    plugins.withId("java-library") {
        java {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        kotlin {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
