plugins {
    id("com.android.application") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.1.21"
}

android {
    namespace = "com.example.sampleplugin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.sampleplugin"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    // CodeOnTheGo Plugin API - REQUIRED
    // Use the prebuilt JAR file located in libs/plugin-api.jar
    compileOnly(files("libs/plugin-api.jar"))

    // Android and Kotlin dependencies
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
    implementation("androidx.fragment:fragment:1.8.8")
}

tasks.wrapper {
    gradleVersion = "8.10.2"
    distributionType = Wrapper.DistributionType.BIN
}

// Task to copy the plugin APK as CGP (CodeOnTheGo Plugin)
tasks.register<Copy>("assemblePlugin") {
    group = "build"
    description = "Builds and packages the plugin as a .cgp file"

    dependsOn("assembleRelease")

    from(layout.buildDirectory.file("outputs/apk/release/sample-plugin-release-unsigned.apk"))
    into(layout.buildDirectory.dir("plugin"))
    rename { "sample-plugin.cgp" }

    doLast {
        logger.lifecycle("Plugin CGP created: ${layout.buildDirectory.get().asFile.absolutePath}/plugin/sample-plugin.cgp")
    }
}

// Also create a debug CGP for testing
tasks.register<Copy>("assemblePluginDebug") {
    group = "build"
    description = "Builds and packages the debug plugin as a .cgp file"

    dependsOn("assembleDebug")

    from(layout.buildDirectory.file("outputs/apk/debug/sample-plugin-debug.apk"))
    into(layout.buildDirectory.dir("plugin"))
    rename { "sample-plugin-debug.cgp" }

    doLast {
        logger.lifecycle("Debug plugin CGP created: ${layout.buildDirectory.get().asFile.absolutePath}/plugin/sample-plugin-debug.cgp")
    }
}