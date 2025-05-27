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
    `kotlin-dsl`
}

repositories {
  google()
  gradlePluginPortal()
  mavenCentral()
}

dependencies {
  implementation(libs.composite.constants)
  implementation(projects.buildLogic.common)
  implementation(projects.buildLogic.desugaring)
  implementation(projects.buildLogic.propertiesParser)

  implementation("com.android.tools.build:gradle:${libs.versions.agp.asProvider().get()}")
  implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.asProvider().get()}")
  implementation(libs.maven.publish)

  implementation(libs.common.jkotlin)
  implementation(libs.common.antlr4)
  implementation(libs.google.gson)
  implementation(libs.google.java.format)
}

gradlePlugin {
  plugins {
    create("com.itsaky.androidide.build") {
      id = "com.itsaky.androidide.build"
      implementationClass = "com.itsaky.androidide.plugins.AndroidIDEPlugin"
    }
    create("com.itsaky.androidide.build.propsparser") {
      id = "com.itsaky.androidide.build.propsparser"
      implementationClass = "com.itsaky.androidide.plugins.PropertiesParserPlugin"
    }
    create("com.itsaky.androidide.build.lexergenerator") {
      id = "com.itsaky.androidide.build.lexergenerator"
      implementationClass = "com.itsaky.androidide.plugins.LexerGeneratorPlugin"
    }
    create("com.itsaky.androidide.build.external-assets") {
      id = "com.itsaky.androidide.build.external-assets"
      implementationClass = "com.itsaky.androidide.plugins.ExternalAssetsPlugin"
    }
  }
}
