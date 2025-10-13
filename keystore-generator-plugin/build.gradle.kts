plugins {
    id("com.android.application") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.1.21"
}

android {
    namespace = "com.appdevforall.keygen.plugin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.appdevforall.keygen.plugin"
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
    // AndroidIDE Plugin API
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

    // BouncyCastle for keystore generation
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78")
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

    from(layout.buildDirectory.file("outputs/apk/release/keystore-generator-plugin-release-unsigned.apk"))
    into(layout.buildDirectory.dir("plugin"))
    rename { "keystore-generator.cgp" }

    doLast {
        logger.lifecycle("Plugin CGP created: ${layout.buildDirectory.get().asFile.absolutePath}/plugin/keystore-generator.cgp")
    }
}

// Also create a debug CGP for testing
tasks.register<Copy>("assemblePluginDebug") {
    group = "build"
    description = "Builds and packages the debug plugin as a .cgp file"

    dependsOn("assembleDebug")

    from(layout.buildDirectory.file("outputs/apk/debug/keystore-generator-plugin-debug.apk"))
    into(layout.buildDirectory.dir("plugin"))
    rename { "keystore-generator-debug.cgp" }

    doLast {
        logger.lifecycle("Debug plugin CGP created: ${layout.buildDirectory.get().asFile.absolutePath}/plugin/keystore-generator-debug.cgp")
    }
}