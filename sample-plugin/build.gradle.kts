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
    compileOnly(project(":plugin-api"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

tasks.register("pluginJar") {
    dependsOn("assembleRelease")
    doLast {
        val aarFile = File(layout.buildDirectory.asFile.get(), "outputs/aar/sample-plugin-v7-release.aar")
        val jarFile = File(layout.buildDirectory.asFile.get(), "outputs/hello-world-plugin.jar")
        val tempDir = File(layout.buildDirectory.asFile.get(), "plugin-temp")
        val dexDir = File(tempDir, "dex")
        
        if (aarFile.exists()) {
            // Clean temp directory
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
            tempDir.mkdirs()
            dexDir.mkdirs()
            
            // Extract classes.jar from AAR (contains Java bytecode)
            copy {
                from(zipTree(aarFile))
                into(tempDir)
                include("classes.jar")
            }
            
            val classesJar = File(tempDir, "classes.jar")
            if (classesJar.exists()) {
                // Use D8 to convert Java bytecode to DEX
                val d8Tool = File("${android.sdkDirectory}/build-tools/${android.buildToolsVersion}/d8")
                if (d8Tool.exists()) {
                    try {
                        project.exec {
                            commandLine(
                                d8Tool.absolutePath,
                                "--output", dexDir.absolutePath,
                                "--min-api", android.defaultConfig.minSdk,
                                classesJar.absolutePath
                            )
                        }
                        
                        // Move classes.dex to temp directory root
                        val classesDex = File(dexDir, "classes.dex")
                        if (classesDex.exists()) {
                            classesDex.copyTo(File(tempDir, "classes.dex"), overwrite = true)
                        }
                        
                        println("Successfully converted to DEX format")
                    } catch (e: Exception) {
                        println("Failed to convert to DEX, using Java bytecode: ${e.message}")
                        // Fallback: extract Java bytecode
                        copy {
                            from(zipTree(classesJar))
                            into(tempDir)
                        }
                    }
                } else {
                    println("D8 tool not found, using Java bytecode")
                    // Fallback: extract Java bytecode
                    copy {
                        from(zipTree(classesJar))
                        into(tempDir)
                    }
                }
                
                // Clean up
                classesJar.delete()
                dexDir.deleteRecursively()
            }
            
            // Copy plugin.json to root
            val pluginJsonSrc = File(projectDir, "src/main/resources/plugin.json")
            if (pluginJsonSrc.exists()) {
                pluginJsonSrc.copyTo(File(tempDir, "plugin.json"), overwrite = true)
            }
            
            // Create final JAR
            ant.withGroovyBuilder {
                "jar"("destfile" to jarFile.absolutePath, "basedir" to tempDir.absolutePath)
            }
            
            println("Generated plugin JAR: ${jarFile.absolutePath}")
            println("Plugin JAR contents:")
            project.exec {
                commandLine("jar", "-tf", jarFile.absolutePath)
                standardOutput = System.out
            }
        } else {
            println("AAR file not found: ${aarFile.absolutePath}")
        }
    }
}