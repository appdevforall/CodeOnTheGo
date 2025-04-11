import com.itsaky.androidide.build.config.BuildConfig

plugins {
    id("java")
    kotlin("jvm") version "1.9.22"
}

/**
 * This is required so the constants project would know and take hold of it's contents.
 */
sourceSets {
    all {
        java {
            //srcDir("../templates-api/src/main/java/com/itsaky/androidide/templates/Sdk.kt")
            srcDirs("src/main/java/com/adfa/constants")
        }
    }
}

subprojects {
    plugins.withId("java-library") {
        extensions.getByType(JavaPluginExtension::class.java).apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}