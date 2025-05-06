plugins {
    id("java")
    kotlin("jvm") version "1.9.22"
}

allprojects {
    plugins.withId("java-library") {
        java {
            toolchain {
                languageVersion = JavaLanguageVersion.of(17)
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        kotlin {
            jvmToolchain(17)
        }
    }
}
