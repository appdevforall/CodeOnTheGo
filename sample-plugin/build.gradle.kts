
plugins {
    id("com.android.library") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.1.21"
}

android {
    namespace = "com.example.sampleplugin"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
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
}

dependencies {
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    compileOnly("com.itsaky.androidide:plugin-api") {
        attributes {
            attribute(Attribute.of("abi", String::class.java), "v8")
        }
    }
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
    implementation("androidx.fragment:fragment:1.8.8")
}

tasks.wrapper {
    gradleVersion = "8.10.2"
    distributionType = Wrapper.DistributionType.BIN
}

abstract class PluginJarTask : DefaultTask() {

    @get:InputFile
    abstract val aarFile: RegularFileProperty

    @get:InputFile
    abstract val pluginManifest: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:Internal
    abstract val androidSdkDirectory: DirectoryProperty

    @get:Internal
    abstract val buildToolsVersion: Property<String>

    @get:Internal
    abstract val minApiLevel: Property<Int>

    @TaskAction
    fun createPluginJar() {
        val tempDir = temporaryDir.resolve("plugin-temp")
        val dexDir = tempDir.resolve("dex")

        // Clean and create temp directories
        tempDir.deleteRecursively()
        tempDir.mkdirs()
        dexDir.mkdirs()

        // Extract classes.jar from AAR
        project.copy {
            from(project.zipTree(aarFile.asFile.get()))
            into(tempDir)
            include("classes.jar")
        }

        val classesJar = tempDir.resolve("classes.jar")

        if (classesJar.exists()) {
            val success = convertToDex(classesJar, dexDir, tempDir)
            if (!success) {
                // Fallback: use Java bytecode
                logger.warn("Using Java bytecode fallback")
                extractJavaBytecode(classesJar, tempDir)
            }
            classesJar.delete()
        }

        // Copy plugin manifest
        if (pluginManifest.asFile.get().exists()) {
            pluginManifest.asFile.get().copyTo(tempDir.resolve("plugin.json"))
        }

        // Create final JAR
        project.ant.withGroovyBuilder {
            "jar"("destfile" to outputJar.asFile.get().absolutePath, "basedir" to tempDir.absolutePath)
        }

        logger.lifecycle("Generated plugin JAR: ${outputJar.asFile.get().absolutePath}")

        // Clean up
        dexDir.deleteRecursively()
    }

    private fun convertToDex(classesJar: File, dexDir: File, tempDir: File): Boolean {
        val d8Tool = androidSdkDirectory.asFile.get()
            .resolve("build-tools")
            .resolve(buildToolsVersion.get())
            .resolve("d8")

        if (!d8Tool.exists()) {
            logger.warn("D8 tool not found at: ${d8Tool.absolutePath}")
            return false
        }

        return try {
            project.exec {
                commandLine(
                    d8Tool.absolutePath,
                    "--output", dexDir.absolutePath,
                    "--min-api", minApiLevel.get().toString(),
                    classesJar.absolutePath
                )
            }

            // Move classes.dex to temp directory root
            val classesDex = dexDir.resolve("classes.dex")
            if (classesDex.exists()) {
                classesDex.copyTo(tempDir.resolve("classes.dex"))
                logger.lifecycle("Successfully converted to DEX format")
                true
            } else {
                logger.warn("DEX conversion failed - no classes.dex generated")
                false
            }
        } catch (e: Exception) {
            logger.warn("DEX conversion failed: ${e.message}")
            false
        }
    }

    private fun extractJavaBytecode(classesJar: File, tempDir: File) {
        project.copy {
            from(project.zipTree(classesJar))
            into(tempDir)
        }
    }
}

tasks.register<PluginJarTask>("pluginJar") {
    group = "build"
    description = "Creates a plugin JAR with DEX bytecode for Android compatibility"

    dependsOn("assembleRelease")

    aarFile.set(layout.buildDirectory.file("outputs/aar/sample-plugin-release.aar"))
    pluginManifest.set(layout.projectDirectory.file("src/main/resources/plugin.json"))
    outputJar.set(layout.buildDirectory.file("outputs/hello-world-plugin.jar"))
    androidSdkDirectory.set(layout.dir(provider { android.sdkDirectory }))
    buildToolsVersion.set(android.buildToolsVersion)
    minApiLevel.set(android.defaultConfig.minSdk ?: 26)
}