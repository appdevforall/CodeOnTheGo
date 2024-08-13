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

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

repositories {
  google()
  gradlePluginPortal()
  mavenCentral()
}
/**
 * Keywords: [build, sdk, common, init-script, path, templates]
 * !!! important !!! This is a crude bridge to build-logic plugin.
 * Everything written here is just up to my understanding and the fact that I
 * don't want to change the whole project structure to keep it comaptible with
 * the root repo.
 * I have added additional source dir to this module that creates an IMPLICIT
 * dependecy on build-logic plugin.
 *
 * Why I did this?
 * I needed common constants and sdk files to start the process of forming single source of
 * truth for different constants. And also I needed flexibility, current implmentation only
 * works with one gradle file name(version) and there is no way to change it without rewriting
 * code.
 * We have the following project structure.
 * = build-logic plugin =
 * = ide-app/all modules =
 * And I need those constants in both components.
 *
 * Why I did it this way?
 * Plugin and project are esentially unrelated and have no info about each other until
 * a connection is established throught
 * @see build.gradle.kts (root androidIDE) id("build-logic.root-project")
 * at this point ide-app gets access to new tasks introduced by the plugin, but that's it.
 * App does not have access to the code inside the plugin. And plugin has no idea about app code
 * and no access to classes.
 *
 * So, in order to provide same set of constants to both the app and plugin I had to
 * come up with some way of sharing this info.
 * I had 3 ways of fixing that:
 * 1) Creating a common module that I will provide both for plugin and the app.
 * But  build-logic plugin is a project of its own. And it can only access it's internal
 * modules(I guess it makes sense, it work just the same for any other project).
 * So I wasn't able to add any common modules
 * 2) Moving sources to build-logic and add sourceSets only in :common module.
 * But I was not able to exclude all the other sources besides sdk and constants file.
 * For some reason exclude just doesn't exist for android sourcests.
 * 3) Add this code from :templatesAPI as a sourceSet to a new module :constants that will contain
 * only constants. And include that new :constants module everywhere I need it except for
 * :templatesAPI to avoid circular dependencies.
 * Selected Solution:
 * I have moved sdk.kt and constants.kt files from templatesApi to :constants.
 * I have addes a sourceSet to :constants module to force it to recognize it's own code.
 * I have imported :constants into :common. Regular implementation(projects....)
 * I have bruteforced :constants code into :build-logic using sourceSet.
 *
 * !!! IMPORTANT !!!
 * This solution will make merging a bit harder, we will have to manually move changes
 * to :constants module, but it is the best way I was able to do it so far.
 *
 * Now we have the following structure.
 *
 * - implememntation relationshp
 * = sourceSet relationshp
 *
 * :constants - :common
 * :constants = :build-logic
 *
 * This way we have the same constants in both the project and unreachable by other means plugin.
 */

sourceSets {
  all {
    java {
      srcDirs("plugins/src/main/java/com/itsaky/androidide", "../../../constants/src/main/java/com/adfa/constants")
    }
  }
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "17"
}

dependencies {
  implementation(projects.buildLogic.common)
  implementation(projects.buildLogic.desugaring)
  implementation(projects.buildLogic.propertiesParser)

  implementation("com.android.tools.build:gradle:${libs.versions.agp.asProvider().get()}")
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
  }
}
