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

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.itsaky.androidide.build.config.BuildConfig

@Suppress("JavaPluginLanguageLevel")
plugins {
	id("com.github.johnrengelman.shadow") version "8.1.1"
	id("java-library")
	id("kotlin-kapt")
	id("org.jetbrains.kotlin.jvm")
}

tasks.withType<Jar> {
	manifest { attributes("Main-Class" to "${BuildConfig.PACKAGE_NAME}.tooling.impl.Main") }
}

tasks.register("deleteExistingJarFiles") {
	delete {
		delete(project.layout.buildDirectory.dir("libs"))
	}
}

tasks.register("copyJar") {
	doLast {
		val libsDir = project.layout.buildDirectory.dir("libs")

		copy {
			from(libsDir)
			into(libsDir)
			include("*-all.jar")
			rename { "tooling-api-all.jar" }
		}
	}
}

project.tasks.getByName<Jar>("jar") {
	dependsOn("deleteExistingJarFiles")
	finalizedBy("shadowJar")
	entryCompression = ZipEntryCompression.STORED
}

project.tasks.getByName<ShadowJar>("shadowJar") {
	finalizedBy("copyJar")
	entryCompression = ZipEntryCompression.STORED
}

dependencies {
	kapt(libs.google.auto.service)

	api(projects.subprojects.toolingApi)

	implementation(projects.buildInfo)
	implementation(projects.shared)
	implementation(projects.subprojects.projectModels)

	implementation(libs.common.jkotlin)
	implementation(libs.google.auto.service.annotations)
	implementation(libs.xml.xercesImpl)
	implementation(libs.xml.apis)
	implementation(libs.tooling.gradleApi)

	testImplementation(projects.testing.tooling)

	runtimeOnly(libs.tooling.slf4j)
}
