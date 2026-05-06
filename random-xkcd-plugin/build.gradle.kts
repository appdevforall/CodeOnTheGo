plugins {
    id("com.android.application") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.1.21"
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "random-xkcd"
}

android {
    namespace = "com.codeonthego.xkcdrandom"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.codeonthego.xkcdrandom"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly(project(":plugin-api"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
    implementation("androidx.fragment:fragment:1.8.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OkHttp for the xkcd JSON endpoint + image fetch.
    // Kept tiny and dependency-free — no Glide/Retrofit, since this plugin is a
    // teaching example and we want the network layer to read top-to-bottom.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}

tasks.wrapper {
    gradleVersion = "8.10.2"
    distributionType = Wrapper.DistributionType.BIN
}
