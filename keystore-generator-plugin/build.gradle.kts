plugins {
    id("com.android.application") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.1.21"
    id("com.itsaky.androidide.plugins.build")
}

pluginBuilder {
    pluginName = "keystore-generator"
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

val bcprovOriginal: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val stripBcprovPqc by tasks.registering(Jar::class) {
    archiveFileName.set("bcprov-nopqc.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from({ bcprovOriginal.map { zipTree(it) } }) {
        exclude("org/bouncycastle/pqc/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

dependencies {
    compileOnly(project(":plugin-api"))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
    implementation("androidx.fragment:fragment:1.8.8")

    // BouncyCastle for keystore generation (PQC classes stripped via stripBcprovPqc task)
    bcprovOriginal("org.bouncycastle:bcprov-jdk18on:1.78")
    implementation(files(stripBcprovPqc.flatMap { it.archiveFile }))
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
    }
}

tasks.wrapper {
    gradleVersion = "8.10.2"
    distributionType = Wrapper.DistributionType.BIN
}