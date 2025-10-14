package com.itsaky.androidide.tooling.impl.serial

import com.android.builder.model.v2.dsl.BuildType
import com.android.builder.model.v2.ide.AndroidArtifact
import com.android.builder.model.v2.ide.AndroidLibraryData
import com.android.builder.model.v2.ide.ArtifactDependencies
import com.android.builder.model.v2.ide.GraphItem
import com.android.builder.model.v2.ide.JavaCompileOptions
import com.android.builder.model.v2.ide.Library
import com.android.builder.model.v2.ide.LibraryInfo
import com.android.builder.model.v2.ide.LibraryType
import com.android.builder.model.v2.ide.ProjectInfo
import com.android.builder.model.v2.ide.ProjectType
import com.android.builder.model.v2.ide.SourceProvider
import com.android.builder.model.v2.ide.SourceSetContainer
import com.android.builder.model.v2.ide.UnresolvedDependency
import com.android.builder.model.v2.ide.Variant
import com.android.builder.model.v2.ide.ViewBindingOptions
import com.android.builder.model.v2.models.AndroidDsl
import com.android.builder.model.v2.models.AndroidProject
import com.android.builder.model.v2.models.BasicAndroidProject
import com.android.builder.model.v2.models.VariantDependencies
import com.android.builder.model.v2.models.Versions
import com.itsaky.androidide.project.AndroidArtifact
import com.itsaky.androidide.project.AndroidLibraryData
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.AndroidProject
import com.itsaky.androidide.project.AndroidVariant
import com.itsaky.androidide.project.ArtifactDependencies
import com.itsaky.androidide.project.GraphItem
import com.itsaky.androidide.project.JavaCompilerSettings
import com.itsaky.androidide.project.Library
import com.itsaky.androidide.project.LibraryInfo
import com.itsaky.androidide.project.ProjectInfo
import com.itsaky.androidide.project.SourceProvider
import com.itsaky.androidide.project.SourceSetContainer
import com.itsaky.androidide.project.UnresolvedDependency
import com.itsaky.androidide.project.VariantDependencies
import com.itsaky.androidide.project.ViewBindingOptions
import com.itsaky.androidide.utils.AndroidPluginVersion
import com.itsaky.androidide.utils.capitalizeString
import java.io.File

fun createAndroidProjectProtoModel(
	basicAndroidProject: BasicAndroidProject,
	androidProject: AndroidProject,
	androidDsl: AndroidDsl,
	versions: Versions,
	variantDependencies: VariantDependencies,
	configuredVariantName: String?,
	classesJar: File,
) = AndroidProject(
	namespace = androidProject.namespace,
	projectType = when (basicAndroidProject.projectType) {
		ProjectType.APPLICATION -> AndroidModels.ProjectType.ApplicationProject
		ProjectType.LIBRARY -> AndroidModels.ProjectType.LibraryProject
		ProjectType.DYNAMIC_FEATURE -> AndroidModels.ProjectType.DynamicFeature
		ProjectType.TEST -> AndroidModels.ProjectType.Test
		ProjectType.FUSED_LIBRARY -> AndroidModels.ProjectType.FusedLibrary
	},
	mainSourceSet = basicAndroidProject.mainSourceSet?.asProtoModel(),
	javaCompilerSettings = androidProject.javaCompileOptions?.asProtoModel(),
	viewBindingOptions = androidProject.viewBindingOptions?.asProtoModel(),
	bootClassPathsList = basicAndroidProject.bootClasspath.map { file -> file.absolutePath },
	variantList = androidProject.variants.map {
		it.asProtoModel(
			basicAndroidProject,
			androidProject,
			androidDsl,
			versions
		)
	},
	configuredVariantName = configuredVariantName,
	classesJarPath = classesJar.absolutePath,
	variantDependencies = variantDependencies.asProtoModel(),
)

fun VariantDependencies.asProtoModel() = VariantDependencies(
	name = this.name,
	mainArtifact = this.mainArtifact.asProtoModel(),
	librariesMap = this.libraries.mapValues { entry -> entry.value.asProtoModel() }
)

fun Library.asProtoModel() = Library(
	key = this.key,
	type = when (this.type) {
		LibraryType.PROJECT -> AndroidModels.LibraryType.Project
		LibraryType.ANDROID_LIBRARY -> AndroidModels.LibraryType.ExternalAndroidLibrary
		LibraryType.JAVA_LIBRARY -> AndroidModels.LibraryType.ExternalJavaLibrary
		LibraryType.RELOCATED -> AndroidModels.LibraryType.Relocated
		LibraryType.NO_ARTIFACT_FILE -> AndroidModels.LibraryType.NoArtifactFile
	},
	projectInfo = this.projectInfo?.asProtoModel(),
	libraryInfo = this.libraryInfo?.asProtoModel(),
	androidLibraryData = this.androidLibraryData?.asProtoModel(),
	artifactPath = this.artifact?.absolutePath,
)

fun AndroidLibraryData.asProtoModel() = AndroidLibraryData(
	compileJarFilePathsList = this.compileJarFiles.map { it.absolutePath },
	manifestFilePath = this.manifest.absolutePath,
	resFolderPath = this.resFolder.absolutePath,
)

fun ProjectInfo.asProtoModel() = ProjectInfo(
	buildId = this.buildId,
	projectPath = projectPath
)

fun LibraryInfo.asProtoModel() = LibraryInfo(
	group = this.group,
	name = this.name,
	version = this.version
)


fun ArtifactDependencies.asProtoModel() = ArtifactDependencies(
	compileDependencyList = this.compileDependencies.map { it.asProtoModel() },
	unresolvedDependencyList = this.unresolvedDependencies.map { it.asProtoModel() },
)

fun GraphItem.asProtoModel(): AndroidModels.GraphItem = GraphItem(
	key = this.key,
	requestedCoordinates = this.requestedCoordinates,
	dependencyList = this.dependencies.map { it.asProtoModel() }
)

fun UnresolvedDependency.asProtoModel() = UnresolvedDependency(
	name = this.name,
	cause = this.cause,
)

fun SourceSetContainer.asProtoModel() =
	SourceSetContainer(sourceProvider = this.sourceProvider?.asProtoModel())

fun SourceProvider.asProtoModel() = SourceProvider(
	javaDirsList = this.javaDirectories.map { dir -> dir.absolutePath },
	kotlinDirsList = this.kotlinDirectories.map { dir -> dir.absolutePath },
	resDirsList = this.resDirectories?.map { dir -> dir.absolutePath } ?: emptyList()
)

fun JavaCompileOptions.asProtoModel() = JavaCompilerSettings(
	sourceCompatibility = this.sourceCompatibility,
	targetCompatibility = this.targetCompatibility,
)

fun ViewBindingOptions.asProtoModel() = ViewBindingOptions(
	isEnabled = this.isEnabled
)

fun Variant.asProtoModel(
	basicAndroidProject: BasicAndroidProject,
	androidProject: AndroidProject,
	androidDsl: AndroidDsl,
	versions: Versions,
) = AndroidVariant(
	name = this.name,
	mainArtifact = this.mainArtifact.asProtoModel(
		artifactName = "main",
		variantName = this.name,
		basicAndroidProject = basicAndroidProject,
		androidProject = androidProject,
		androidDsl = androidDsl,
		versions = versions
	)
)

fun AndroidArtifact.asProtoModel(
	artifactName: String,
	variantName: String,
	basicAndroidProject: BasicAndroidProject,
	androidProject: AndroidProject,
	androidDsl: AndroidDsl,
	versions: Versions,
) = AndroidArtifact(
	name = artifactName,
	applicationId = computeApplicationId(
		variantName,
		basicAndroidProject,
		androidProject,
		androidDsl,
		versions
	),
	resGenTaskName = this.resGenTaskName,
	sourceGenTaskName = this.sourceGenTaskName,
	compileTaskName = this.compileTaskName,
	assembleTaskName = this.assembleTaskName,
	assembleTaskOutputListingFilePath = this.assembleTaskOutputListingFile?.absolutePath,
	generatedResourceFolderPathsList = this.generatedResourceFolders.map { it.absolutePath },
	generatedSourceFolderPathsList = this.generatedSourceFolders.map { it.absolutePath },
	maxSdkVersion = this.maxSdkVersion,
	minSdkVersion = this.minSdkVersion.apiLevel,
	classJarPathsList = this.classesFolders.mapNotNull { classesFolder ->
		classesFolder.absolutePath.takeIf { path ->
			path.endsWith(
				".jar"
			)
		}
	},
	targetSdkVersionOverride = this.targetSdkVersionOverride?.apiLevel ?: 1,
)

private fun AndroidArtifact.computeApplicationId(
	variantName: String,
	basicAndroidProject: BasicAndroidProject,
	androidProject: AndroidProject,
	androidDsl: AndroidDsl,
	versions: Versions,
): String? {
	val minAgpForAppId = AndroidPluginVersion(7, 4, 0)
	return if (minAgpForAppId <= AndroidPluginVersion.parse(versions.agp)) {
		applicationId
	} else {
		computeApplicationIdLegacy(variantName, basicAndroidProject, androidProject, androidDsl)
	}
}

// Adapted from the following :
// https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/internal/core/dsl/impl/ComponentDslInfoImpl.kt;drc=6a5551bdea55c0c991f1ccf1e3f8f6f3d2cd2cb7;l=107
// https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/internal/core/dsl/impl/VariantDslInfoImpl.kt;drc=d44f5b98cd5530eceb230e0d151ad96c4277f78d;l=109

private fun computeApplicationIdLegacy(
	variantName: String,
	basicAndroidProject: BasicAndroidProject,
	androidProject: AndroidProject,
	androidDsl: AndroidDsl,
): String {
	val basicVariant = basicAndroidProject.variants.firstOrNull { it.name == variantName }
	val buildType = basicVariant?.buildType?.let { buildTypeName ->
		androidDsl.buildTypes.find { buildType -> buildType.name == buildTypeName }
	}!!

	val appIdFromFlavor = if (basicAndroidProject.projectType == ProjectType.APPLICATION) {
		androidDsl.productFlavors.find { flavor ->
			"${flavor.name}${buildType.name.capitalizeString()}" == variantName
		}?.applicationId
	} else {
		androidDsl.defaultConfig.applicationId
	}

	return if (appIdFromFlavor == null) {
		// No appId value set from DSL; use the namespace value from the DSL.
		"${androidProject.namespace}${
			computeApplicationIdSuffix(
				variantName,
				buildType,
				basicAndroidProject,
				androidDsl
			)
		}"
	} else {
		// use value from flavors/defaultConfig
		// needed to make nullability work in kotlinc
		val finalAppIdFromFlavors: String = appIdFromFlavor
		"$finalAppIdFromFlavors${
			computeApplicationIdSuffix(
				variantName,
				buildType,
				basicAndroidProject,
				androidDsl
			)
		}"
	}
}

/**
 * Combines all the appId suffixes into a single one.
 *
 * The suffixes are separated by '.' whether their first char is a '.' or not.
 */
private fun computeApplicationIdSuffix(
	variantName: String,
	buildType: BuildType,
	basicAndroidProject: BasicAndroidProject,
	androidDsl: AndroidDsl,
): String {
	// for the suffix we combine the suffix from all the flavors. However, we're going to
	// want the higher priority one to be last.
	val suffixes = mutableListOf<String>()
	androidDsl.defaultConfig.applicationIdSuffix?.let {
		suffixes.add(it)
	}

	if (basicAndroidProject.projectType == ProjectType.APPLICATION) {

		val flavorSuffix = androidDsl.productFlavors.find { flavor ->
			"${flavor.name}${buildType.name.capitalizeString()}" == variantName
		}?.applicationIdSuffix

		flavorSuffix?.also { suffixes.add(flavorSuffix) }

		// then we add the build type after.
		buildType.applicationIdSuffix?.also {
			suffixes.add(it)
		}
	}

	val nonEmptySuffixes = suffixes.filter { it.isNotEmpty() }
	return if (nonEmptySuffixes.isNotEmpty()) {
		".${nonEmptySuffixes.joinToString(separator = ".", transform = { it.removePrefix(".") })}"
	} else {
		""
	}
}
