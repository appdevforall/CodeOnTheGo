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

import com.android.SdkConstants
import com.android.aaptcompiler.AaptResourceType
import com.android.aaptcompiler.ResourceTable
import com.google.protobuf.MessageLite
import com.itsaky.androidide.builder.model.UNKNOWN_PACKAGE
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.models.artifact
import com.itsaky.androidide.projects.models.bootClassPaths
import com.itsaky.androidide.projects.models.classJars
import com.itsaky.androidide.projects.models.classesJar
import com.itsaky.androidide.projects.models.compileJarFiles
import com.itsaky.androidide.projects.models.generatedSourceFolders
import com.itsaky.androidide.projects.models.javaDirs
import com.itsaky.androidide.projects.models.kotlinDirs
import com.itsaky.androidide.projects.models.resDirs
import com.itsaky.androidide.projects.models.resFolder
import com.itsaky.androidide.tooling.api.util.findPackageName
import com.itsaky.androidide.utils.withStopWatch
import com.itsaky.androidide.xml.resources.ResourceTableRegistry
import com.itsaky.androidide.xml.versions.ApiVersions
import com.itsaky.androidide.xml.versions.ApiVersionsRegistry
import com.itsaky.androidide.xml.widgets.WidgetTable
import com.itsaky.androidide.xml.widgets.WidgetTableRegistry
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * A [GradleProject] model implementation for Android modules which is exposed to other modules and
 * provides additional helper methods.
 *
 * @author Akash Yadav
 */
open class AndroidModule(
	delegate: GradleModels.GradleProject,
) : ModuleProject(delegate), AndroidModels.AndroidProjectOrBuilder by delegate.androidProject {

	companion object {

		private val log = LoggerFactory.getLogger(AndroidModule::class.java)
	}

	init {
		check(delegate.hasAndroidProject()) {
			"Project is not an Android module: $path"
		}
	}

	override fun isInitialized(): Boolean {
		return super.isInitialized()
	}

	override fun getDefaultInstanceForType(): MessageLite? {
		return super.getDefaultInstanceForType()
	}

	fun getGeneratedJar(): File {
		return classesJar ?: File("does-not-exist.jar")
	}

	override fun getClassPaths(): Set<File> {
		return getModuleClasspaths()
	}

	fun getVariant(name: String): AndroidModels.AndroidVariant? {
		return variantList.firstOrNull { it.name == name }
	}

	fun getResourceDirectories(): Set<File> {
		if (mainSourceSet == null) {
			log.error("No main source set found in application module: {}", name)
			return emptySet()
		}

		val dirs = mutableSetOf<File>()
		dirs.addAll(mainSourceSet?.sourceProvider?.resDirs ?: emptyList())

		val dependencies = getCompileModuleProjects().filterIsInstance<AndroidModule>()

		for (dependency in dependencies) {
			dirs.addAll(dependency.getResourceDirectories())
		}

		return dirs
	}

	override fun getSourceDirectories(): Set<File> {
		if (mainSourceSet == null) {
			log.warn(
				"No main source set is available for project {}. Cannot get source directories.",
				name
			)
			return mutableSetOf()
		}

		// src/main/java
		val sources = mainSourceSet.sourceProvider.javaDirs.toMutableSet()

		// src/main/kotlin
		sources.addAll(mainSourceSet.sourceProvider.kotlinDirs)

		// build/generated/**
		// AIDL, ViewHolder, Renderscript, BuildConfig i.e every generated source sources
		val selectedVariant = getSelectedVariant()
		if (selectedVariant != null) {
			sources.addAll(selectedVariant.mainArtifact.generatedSourceFolders)
		}
		return sources
	}

	override fun getCompileSourceDirectories(): Set<File> {
		val dirs = mutableSetOf<File>()
		dirs.addAll(getSourceDirectories())
		getCompileModuleProjects().forEach { dirs.addAll(it.getSourceDirectories()) }
		return dirs
	}

	override fun getModuleClasspaths(): Set<File> {
		return mutableSetOf<File>().apply {
			add(getGeneratedJar())
			addAll(getSelectedVariant()?.mainArtifact?.classJars ?: emptyList())
		}
	}

	override fun getCompileClasspaths(): Set<File> {
		val project = IProjectManager.getInstance().workspace ?: return emptySet()
		val result = mutableSetOf<File>()
		result.addAll(getModuleClasspaths())
		collectLibraries(
			root = project,
			libraries = variantDependencies.mainArtifact?.compileDependencyList ?: emptyList(),
			result = result
		)
		return result
	}

	private fun collectLibraries(
		root: Workspace,
		libraries: List<AndroidModels.GraphItem>,
		result: MutableSet<File>
	) {
		val libraryMap = variantDependencies.librariesMap
		for (library in libraries) {
			val lib = libraryMap[library.key] ?: continue
			if (lib.type == AndroidModels.LibraryType.Project) {
				val module = root.findByPath(lib.projectInfo!!.projectPath) ?: continue
				if (module !is ModuleProject) {
					continue
				}

				result.addAll(module.getCompileClasspaths())
			} else if (lib.type == AndroidModels.LibraryType.ExternalAndroidLibrary && lib.hasAndroidLibraryData()) {
				result.addAll(lib.androidLibraryData.compileJarFiles)
			} else if (lib.type == AndroidModels.LibraryType.ExternalJavaLibrary && lib.hasArtifactPath()) {
				result.add(lib.artifact)
			}

			collectLibraries(root, library.dependencyList, result)
		}
	}

	override fun getCompileModuleProjects(): List<ModuleProject> {
		val root = IProjectManager.getInstance().workspace ?: return emptyList()
		val result = mutableListOf<ModuleProject>()

		val libraries = variantDependencies.mainArtifact.compileDependencyList
		val libraryMap = variantDependencies.librariesMap
		for (library in libraries) {
			val lib = libraryMap[library.key] ?: continue
			if (lib.type != AndroidModels.LibraryType.Project) {
				continue
			}

			val module = root.findByPath(lib.projectInfo!!.projectPath) ?: continue
			if (module !is ModuleProject) {
				continue
			}

			result.add(module)
			result.addAll(module.getCompileModuleProjects())
		}

		return result
	}

	override fun hasExternalDependency(group: String, name: String): Boolean {
		return variantDependencies.librariesMap.values.any { library ->
			library.libraryInfo?.let { libraryInfo ->
				libraryInfo.group == group && libraryInfo.name == name
			} ?: false
		}
	}

	/**
	 * Reads the resource files are creates the [com.android.aaptcompiler.ResourceTable] instances for
	 * the corresponding resource directories.
	 */
	suspend fun readResources() {
		// Read resources in parallel
		withStopWatch("Read resources for module : $path") {
			val resourceReaderScope = CoroutineScope(
				Dispatchers.IO + CoroutineName("ResourceReader($path)")
			)

			val resourceFlow = flow {
				emit(getFrameworkResourceTable())
				emit(getResourceTable())
				emit(getDependencyResourceTables())
				emit(getApiVersions())
				emit(getWidgetTable())
			}

			val jobs = resourceFlow.map { result ->
				resourceReaderScope.async {
					result
				}
			}

			jobs.toList().awaitAll()
		}
	}

	/**
	 * Get the [ApiVersions] instance for this module.
	 *
	 * @return The [ApiVersions] for this module.
	 */
	fun getApiVersions(): ApiVersions? {
		val platformDir = getPlatformDir()
		if (platformDir != null) {
			return ApiVersionsRegistry.getInstance().forPlatformDir(platformDir)
		}

		return null
	}

	/**
	 * Get the [WidgetTable] instance for this module.
	 *
	 * @return The [WidgetTable] for this module.
	 */
	fun getWidgetTable(): WidgetTable? {
		val platformDir = getPlatformDir()
		if (platformDir != null) {
			return WidgetTableRegistry.getInstance().forPlatformDir(platformDir)
		}

		return null
	}

	/** Get the resource table for this module i.e. without resource tables for dependent modules. */
	fun getResourceTable(): ResourceTable? {
		val namespace = this.namespace ?: return null

		val resDirs = mainSourceSet?.sourceProvider?.resDirs ?: return null
		return ResourceTableRegistry.getInstance().forPackage(namespace, *resDirs.toTypedArray())
	}

	/** Updates the resource table for this module. */
	fun updateResourceTable() {
		if (this.namespace == null) {
			return
		}

		CompletableFuture.runAsync {
			val tableRegistry = ResourceTableRegistry.getInstance()
			val resDirs = mainSourceSet?.sourceProvider?.resDirs ?: return@runAsync
			tableRegistry.removeTable(this.namespace)
			tableRegistry.forPackage(this.namespace, *resDirs.toTypedArray())
		}
	}

	/**
	 * Get the [ResourceTable] instance for this module's compile SDK.
	 *
	 * @return The [ApiVersions] for this module.
	 */
	fun getFrameworkResourceTable(): ResourceTable? {
		val platformDir = getPlatformDir()
		if (platformDir != null) {
			return ResourceTableRegistry.getInstance().forPlatformDir(platformDir)
		}

		return null
	}

	/**
	 * Get the resource tables for this module as well as it's dependent modules.
	 *
	 * @return The set of resource tables. Empty when project is not initalized.
	 */
	fun getSourceResourceTables(): Set<ResourceTable> {
		val set = mutableSetOf(getResourceTable() ?: return emptySet())
		getCompileModuleProjects().filterIsInstance<AndroidModule>().forEach {
			it.getResourceTable()?.also { table -> set.add(table) }
		}
		return set
	}

	/** Get the resource tables for external dependencies (not local module project dependencies). */
	fun getDependencyResourceTables(): Set<ResourceTable> {
		return mutableSetOf<ResourceTable>().also {
			var deps: Int
			val androidLibraries = variantDependencies.librariesMap.values.mapNotNull { library ->
				val packageName = library.androidLibraryData?.findPackageName() ?: UNKNOWN_PACKAGE
				if (library.type != AndroidModels.LibraryType.ExternalAndroidLibrary ||
					!library.hasAndroidLibraryData() ||
					!library.androidLibraryData!!.resFolder.exists() ||
					packageName == UNKNOWN_PACKAGE
				) {
					return@mapNotNull null
				}

				library to packageName
			}

			it.addAll(androidLibraries.also { libs -> deps = libs.size }
				.mapNotNull { (library, packageName) ->
					ResourceTableRegistry.getInstance().let { registry ->
						registry.isLoggingEnabled = false
						registry.forPackage(
							packageName,
							library.androidLibraryData!!.resFolder,
						).also {
							registry.isLoggingEnabled = true
						}
					}
				})

			log.info(
				"Created {} resource tables for {} dependencies of module '{}'", it.size, deps, path
			)
		}
	}

	/**
	 * Checks all the resource tables from this module and returns if any of the tables contain
	 * resources for the the given package.
	 *
	 * @param pck The package to look for.
	 */
	fun findResourceTableForPackage(
		pck: String, hasGroup: AaptResourceType? = null
	): ResourceTable? {
		return findAllResourceTableForPackage(pck, hasGroup).let {
			if (it.isNotEmpty()) {
				return it.first()
			} else null
		}
	}

	/**
	 * Checks all the resource tables from this module and returns if any of the tables contain
	 * resources for the the given package.
	 *
	 * @param pck The package to look for.
	 */
	fun findAllResourceTableForPackage(
		pck: String, hasGroup: AaptResourceType? = null
	): List<ResourceTable> {
		if (pck == SdkConstants.ANDROID_PKG) {
			return getFrameworkResourceTable()?.let { listOf(it) } ?: emptyList()
		}

		val tables: List<ResourceTable> = mutableListOf<ResourceTable>().apply {
			getResourceTable()?.let { add(it) }
			addAll(getSourceResourceTables())
			addAll(getDependencyResourceTables())
		}

		val result = mutableListOf<ResourceTable>()
		for (table in tables) {
			val resPck = table.findPackage(pck) ?: continue
			if (hasGroup == null) {
				result.add(table)
				continue
			}
			if (resPck.findGroup(hasGroup) != null) {
				result.add(table)
				continue
			}
		}

		return emptyList()
	}

	/**
	 * Returns all the resource tables associated with this module (including the framework resource
	 * table).
	 *
	 * @return The associated resource tables.
	 */
	fun getAllResourceTables(): Set<ResourceTable> {
		return mutableSetOf<ResourceTable>().apply {
			getResourceTable()?.let { add(it) }
			getFrameworkResourceTable()?.let { add(it) }
			addAll(getSourceResourceTables())
			addAll(getDependencyResourceTables())
		}
	}

	/** Get the resource table for the attrs_manifest.xml file. */
	fun getManifestAttrTable(): ResourceTable? {
		val platform = getPlatformDir() ?: return null
		return ResourceTableRegistry.getInstance().getManifestAttrTable(platform)
	}

	/** @see ResourceTableRegistry.getActivityActions */
	fun getActivityActions(): List<String> {
		return ResourceTableRegistry.getInstance()
			.getActivityActions(getPlatformDir() ?: return emptyList())
	}

	/** @see ResourceTableRegistry.getBroadcastActions */
	fun getBroadcastActions(): List<String> {
		return ResourceTableRegistry.getInstance()
			.getBroadcastActions(getPlatformDir() ?: return emptyList())
	}

	/** @see ResourceTableRegistry.getServiceActions */
	fun getServiceActions(): List<String> {
		return ResourceTableRegistry.getInstance()
			.getServiceActions(getPlatformDir() ?: return emptyList())
	}

	/** @see ResourceTableRegistry.getCategories */
	fun getCategories(): List<String> {
		return ResourceTableRegistry.getInstance()
			.getCategories(getPlatformDir() ?: return emptyList())
	}

	/** @see ResourceTableRegistry.getFeatures */
	fun getFeatures(): List<String> {
		return ResourceTableRegistry.getInstance()
			.getFeatures(getPlatformDir() ?: return emptyList())
	}

	/**
	 * Returns the build variant that is selected by the user. This may return `null` in
	 * some misconfiguration scenarios.
	 */
	fun getSelectedVariant(): AndroidModels.AndroidVariant? {
		val projectManager = IProjectManager.getInstance()

		val info = projectManager.androidBuildVariants[this.path]
		if (info == null) {
			log.error(
				"Failed to find selected build variant for module: '{}'", this.path
			)
			return null
		}

		val variant = this.getVariant(info.selectedVariant)
		if (variant == null) {
			log.error(
				"Build variant with name '{}' not found.", info.selectedVariant
			)
			return null
		}

		return variant
	}

	private fun getPlatformDir() =
		bootClassPaths.firstOrNull { it.name == "android.jar" }?.parentFile
}
