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
  id("java-library")
  id("com.itsaky.androidide.build.propsparser")
}

tasks.withType<JavaCompile>().configureEach {
  options.compilerArgs.addAll(
    listOf(
      "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
      "--add-exports=java.base/sun.reflect.annotation=ALL-UNNAMED",
    )
  )
}

dependencies {
  // compileOnly, not api: java-compiler must stay resident-only when jdk-compiler is consumed
  // by the isolated javac carrier (ADFA-5053) -- an `api` dependency here would propagate to
  // every consumer's runtime/packaging classpath regardless of how *they* declare their own
  // dependency on this module, duplicating CacheFSInfo/Context/etc. into the carrier dex. The
  // `javac` aggregate module (composite-builds/build-deps/javac) still `api`s both modules
  // itself for its own (resident-only) consumers.
  compileOnly(projects.buildDeps.javaCompiler)
}