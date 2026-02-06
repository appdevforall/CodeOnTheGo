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

package com.itsaky.androidide.projects.api

import com.google.protobuf.MessageLite
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.JavaModels
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.models.buildDir
import com.itsaky.androidide.projects.models.directory
import com.itsaky.androidide.projects.models.jarFile
import java.io.File

/**
 * A [GradleProject] model implementation for Java library modules which is exposed to other modules and
 * provides additional helper methods.
 * @author Akash Yadav
 */
class JavaModule(
	delegate: GradleModels.GradleProject,
) : ModuleProject(delegate),
	JavaModels.JavaProjectOrBuilder by delegate.javaProject {
	companion object {
		const val SCOPE_COMPILE = "COMPILE"
		const val SCOPE_RUNTIME = "RUNTIME"
	}

	init {
		check(delegate.hasJavaProject()) {
			"Project '${delegate.path}' is not a Java project"
		}
	}

	private val classesJar by lazy {
		var jar = File(delegate.buildDir, "libs/${delegate.name}.jar")
		if (jar.exists()) {
			return@lazy jar
		}

		jar = File(delegate.buildDir, "libs")
			.listFiles()
			?.first { delegate.name?.let(it.name::startsWith) ?: false }
			?: File("module-jar-does-not-exist.jar")

		return@lazy jar
	}

	override fun isInitialized(): Boolean = super.isInitialized()

	override fun getDefaultInstanceForType(): MessageLite? = super.getDefaultInstanceForType()

	override fun getClassPaths(): Set<File> = getModuleClasspaths()

	override fun getSourceDirectories(): Set<File> {
		val sources = mutableSetOf<File>()
		contentRootList.forEach { contentRoot ->
			sources.addAll(
				contentRoot.sourceDirectoryList.map { sourceDirectory ->
					sourceDirectory.directory
				},
			)
		}
		return sources
	}

	override fun getCompileSourceDirectories(): Set<File> {
		val dirs = getSourceDirectories().toMutableSet()
		getCompileModuleProjects().forEach { dirs.addAll(it.getSourceDirectories()) }
		return dirs
	}

	override fun getModuleClasspaths(): Set<File> = mutableSetOf(classesJar)

	override fun getCompileClasspaths(): Set<File> {
		val classpaths = getModuleClasspaths().toMutableSet()
		getCompileModuleProjects().forEach { classpaths.addAll(it.getCompileClasspaths()) }
		classpaths.addAll(getDependencyClassPaths())
		return classpaths
	}

	override fun getIntermediateClasspaths(): Set<File> {
		val result = mutableSetOf<File>()
		val buildDirectory = delegate.buildDir

		val kotlinClasses = File(buildDirectory, "tmp/kotlin-classes/main")
		if (kotlinClasses.exists()) {
			result.add(kotlinClasses)
		}

		val javaClasses = File(buildDirectory, "classes/java/main")
		if (javaClasses.exists()) {
			result.add(javaClasses)
		}

		return result
	}

	override fun getRuntimeDexFiles(): Set<File> = emptySet()

	override fun getCompileModuleProjects(): List<ModuleProject> {
		val root = IProjectManager.getInstance().workspace ?: return emptyList()
		return this.dependencyList
			.filter { it.hasModule() && it.scope == SCOPE_COMPILE }
			.mapNotNull { root.findByPath(it.module.projectPath) }
			.filterIsInstance<ModuleProject>()
	}

	override fun hasExternalDependency(
		group: String,
		name: String,
	): Boolean =
		this.dependencyList.any { dependency ->
			dependency.hasExternalLibrary() &&
				dependency.externalLibrary.libraryInfo?.let { artifact ->
					artifact.group == group && artifact.name == name
				} ?: false
		}

	fun getDependencyClassPaths(): Set<File> =
		this.dependencyList
			.mapNotNull { dependency ->
				dependency.jarFile.takeIf { it.exists() }
			}.toHashSet()
}
