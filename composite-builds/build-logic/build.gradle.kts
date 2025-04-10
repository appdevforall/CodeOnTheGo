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
    id("java")
    kotlin("jvm") version "1.9.22"
}
subprojects {
    plugins.withId("java-library") {
        extensions.getByType(JavaPluginExtension::class.java).apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}