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
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    compileOnly(project(":plugin-api-java"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

tasks.register<Jar>("pluginJar") {
    group = "build"
    description = "Creates plugin JAR file"
    
    archiveBaseName.set("hello-world-plugin-java")
    from(sourceSets.main.get().output)
    
    // Include plugin.json from resources
    from("src/main/resources") {
        include("plugin.json")
    }
    
    doLast {
        println("Generated plugin JAR: ${archiveFile.get().asFile.absolutePath}")
    }
}