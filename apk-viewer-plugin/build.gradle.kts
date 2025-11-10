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
    // COGO Plugin API
    compileOnly(project(":plugin-api")) {
        attributes {
            attribute(Attribute.of("abi", String::class.java), "v8")
        }
    }

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
    description = "Builds and packages the APK Viewer plugin as a .cgp file"

    dependsOn("assembleRelease")

    from(layout.buildDirectory.file("outputs/apk/release/apk-viewer-plugin-release-unsigned.apk"))
    into(layout.buildDirectory.dir("plugin"))
    rename { "apk-viewer.cgp" }

    doLast {
        logger.lifecycle("APK Viewer plugin CGP created: ${layout.buildDirectory.get().asFile.absolutePath}/plugin/apk-viewer.cgp")
    }
}

// Also create a debug CGP for testing
tasks.register<Copy>("assemblePluginDebug") {
    group = "build"
    description = "Builds and packages the debug APK Viewer plugin as a .cgp file"

    dependsOn("assembleDebug")

    from(layout.buildDirectory.file("outputs/apk/debug/apk-viewer-plugin-debug.apk"))
    into(layout.buildDirectory.dir("plugin"))
    rename { "apk-viewer-debug.cgp" }

    doLast {
        logger.lifecycle("Debug APK Viewer plugin CGP created: ${layout.buildDirectory.get().asFile.absolutePath}/plugin/apk-viewer-debug.cgp")
    }
}
