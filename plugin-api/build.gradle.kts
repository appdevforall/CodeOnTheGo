

plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-parcelize")
}

android {
    namespace = "com.itsaky.androidide.plugins.api"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Only include Android context for basic Android functionality
    compileOnly("androidx.appcompat:appcompat:1.6.1")
    compileOnly("androidx.fragment:fragment-ktx:1.6.2")
    compileOnly("com.google.android.material:material:1.11.0")
}

// Task to extract classes from AAR and create a JAR
tasks.register<Jar>("createPluginApiJar") {
    group = "publishing"
    description = "Creates a JAR from the AAR for plugin developers"

    archiveBaseName.set("plugin-api")
    archiveVersion.set("1.0.0")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))

    dependsOn("assembleRelease")

    doFirst {
        val aarFile = layout.buildDirectory.file("outputs/aar/plugin-api-release.aar").get().asFile
        if (aarFile.exists()) {
            val tempDir = temporaryDir
            copy {
                from(zipTree(aarFile))
                into(tempDir)
                include("classes.jar")
            }

            val classesJar = File(tempDir, "classes.jar")
            if (classesJar.exists()) {
                from(zipTree(classesJar))
            }
        }
    }

    doLast {
        logger.lifecycle("Plugin API JAR created: ${archiveFile.get().asFile.absolutePath}")
        logger.lifecycle("Plugin developers can add this JAR as a compileOnly dependency")
    }
}