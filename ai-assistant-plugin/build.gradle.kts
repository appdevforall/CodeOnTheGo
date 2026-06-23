plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.3.0"
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "ai-assistant"
}

android {
    namespace = "com.itsaky.androidide.plugins.aiassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itsaky.androidide.plugins.aiassistant"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
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
    compileOnly(project(":plugin-api"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("com.google.android.material:material:1.10.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Markdown rendering
    implementation("io.noties.markwon:core:4.6.2")

    // Plugin dependencies are loaded at runtime by the plugin manager
    // No explicit compile-time dependency on ai-core-plugin needed
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation(project(":plugin-api"))
    testImplementation(project(":plugin-manager"))
}
