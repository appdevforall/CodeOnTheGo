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
	id("com.itsaky.androidide.build")
}

android {
	namespace = "com.google.googlejavaformat"
}

dependencies {
	implementation(libs.google.guava)
	implementation(libs.google.auto.value.annotations)
	implementation(libs.google.auto.service.annotations)

	// NOT projects.buildDeps.javac (the aggregate): java-compiler must stay resident-only when
	// this module is consumed by the isolated javac carrier (ADFA-5053) -- see
	// composite-builds/build-deps/jdk-compiler's identical fix for the full rationale. This
	// module genuinely runs javac's own parser at runtime (that's how it reformats source), so
	// jdk-compiler itself stays a real, bundled dependency.
	implementation(projects.buildDeps.jdkCompiler)
	compileOnly(projects.buildDeps.javaCompiler)

	annotationProcessor(libs.google.auto.value.ap)
	annotationProcessor(libs.google.auto.service)
}
