/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    id("com.android.library")
    kotlin("android")
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
    compileOnly(project(":plugin-api-java"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

tasks.register("pluginJar") {
    dependsOn("assembleRelease")
    doLast {
        val aarFile = File(layout.buildDirectory.asFile.get(), "outputs/aar/sample-plugin-v7-release.aar")
        val jarFile = File(layout.buildDirectory.asFile.get(), "outputs/hello-world-plugin.jar")
        val tempDir = File(layout.buildDirectory.asFile.get(), "plugin-temp")
        
        if (aarFile.exists()) {
            // Clean temp directory
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
            tempDir.mkdirs()
            
            // Extract classes.jar from AAR
            copy {
                from(zipTree(aarFile))
                into(tempDir)
                include("classes.jar")
            }
            
            // Extract contents of classes.jar
            val classesJar = File(tempDir, "classes.jar")
            if (classesJar.exists()) {
                copy {
                    from(zipTree(classesJar))
                    into(tempDir)
                }
                classesJar.delete()
            }
            
            // Copy plugin.json
            val pluginJsonSrc = File(projectDir, "src/main/resources/plugin.json")
            if (pluginJsonSrc.exists()) {
                pluginJsonSrc.copyTo(File(tempDir, "plugin.json"), overwrite = true)
            }
            
            // Create final JAR
            ant.withGroovyBuilder {
                "jar"("destfile" to jarFile.absolutePath, "basedir" to tempDir.absolutePath)
            }
            
            println("Generated plugin JAR: ${jarFile.absolutePath}")
        } else {
            println("AAR file not found: ${aarFile.absolutePath}")
        }
    }
}