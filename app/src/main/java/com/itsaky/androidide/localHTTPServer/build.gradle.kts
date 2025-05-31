import org.gradle.nativeplatform.platform.internal.Architectures
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

val brotliVersion = "1.18.0"
val operatingSystem = DefaultNativePlatform.getCurrentOperatingSystem()

plugins {
    kotlin("jvm") version "1.9.0"
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

group = "org.appdevforall.localwebserver"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("com.moandjiezana.toml:toml4j:0.7.2")
    implementation("com.aayushatharva.brotli4j:brotli4j:$brotliVersion")
    runtimeOnly(
        "com.aayushatharva.brotli4j:native-" +
                if (operatingSystem.isWindows) {
                    if (DefaultNativePlatform.getCurrentArchitecture().isArm()) {
                        "windows-aarch64"
                    } else {
                        "windows-x86_64"
                    }
                } else if (operatingSystem.isMacOsX) {
                    if (DefaultNativePlatform.getCurrentArchitecture().isArm()) {
                        "osx-aarch64"
                    } else {
                        "osx-x86_64"
                    }
                } else if (operatingSystem.isLinux) {
                    if (Architectures.ARM_V7.isAlias(DefaultNativePlatform.getCurrentArchitecture().name)) {
                        "linux-armv7"
                    } else if (Architectures.AARCH64.isAlias(DefaultNativePlatform.getCurrentArchitecture().name)) {
                        "linux-aarch64"
                    } else if (Architectures.X86_64.isAlias(DefaultNativePlatform.getCurrentArchitecture().name)) {
                        "linux-x86_64"
                    } else {
                        throw IllegalStateException("Unsupported architecture: ${DefaultNativePlatform.getCurrentArchitecture().name}")
                    }
                } else {
                    throw IllegalStateException("Unsupported operating system: $operatingSystem")
                } + ":$brotliVersion"
    )
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

application {
    mainClass.set("org.appdevforall.localwebserver.WebServerKtKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
    }
}

kotlin {
    jvmToolchain(17)
} 
