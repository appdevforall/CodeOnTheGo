

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

tasks.register<Copy>("createPluginApiJar") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar"))
    into(layout.buildDirectory.dir("libs"))
    rename { "plugin-api-1.0.0.jar" }
}