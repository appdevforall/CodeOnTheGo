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

import com.itsaky.androidide.build.config.BuildConfig
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	alias(libs.plugins.kotlin.jvm)
}

group = "${BuildConfig.PACKAGE_NAME}.annotations"

dependencies {
	implementation(kotlin("stdlib"))

	implementation(projects.annotations)
	implementation(projects.eventbus)

	implementation(libs.androidx.annotation)
	implementation(libs.common.javapoet)

	implementation(libs.common.greenrobot.java.common)

	implementation(libs.google.auto.service.annotations)
	annotationProcessor(libs.google.auto.service)

	// Generates the required META-INF descriptor to make the processor incremental.
	compileOnly(libs.common.incap)
	annotationProcessor(libs.common.incap.processor)
}

tasks.withType<KotlinCompile> {
	compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
