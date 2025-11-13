package com.itsaky.androidide.tooling.impl.serial

import com.itsaky.androidide.project.Common
import com.itsaky.androidide.project.JavaCompilerSettings
import com.itsaky.androidide.project.JavaContentRoot
import com.itsaky.androidide.project.JavaDependency
import com.itsaky.androidide.project.JavaExternalLibraryDependency
import com.itsaky.androidide.project.JavaModuleDependency
import com.itsaky.androidide.project.JavaProject
import com.itsaky.androidide.project.JavaSourceDirectory
import com.itsaky.androidide.project.LibraryInfo
import com.itsaky.androidide.projects.models.DEFAULT_COMPILER_SETTINGS
import org.gradle.tooling.model.idea.IdeaContentRoot
import org.gradle.tooling.model.idea.IdeaDependency
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import org.gradle.tooling.model.idea.IdeaSourceDirectory

fun createJavaProjectProtoModel(
	ideaProject: IdeaProject,
	ideaModule: IdeaModule,
	moduleNameToPath: Map<String, String>,
) = JavaProject(
	contentRootList = ideaModule.contentRoots.map { it.asProtoModel() },
	dependencyList = ideaModule.dependencies.map { it.asProtoModel(moduleNameToPath) },
	javaCompilerSettings = createCompilerSettings(ideaProject, ideaModule),
)

private fun createCompilerSettings(
	ideaProject: IdeaProject,
	module: IdeaModule,
): Common.JavaCompilerSettings {
	val javaLanguageSettings =
		module.javaLanguageSettings
			?: return createCompilerSettings(ideaProject)
	val languageLevel = javaLanguageSettings.languageLevel
	val targetBytecodeVersion = javaLanguageSettings.targetBytecodeVersion
	if (languageLevel == null || targetBytecodeVersion == null) {
		return createCompilerSettings(ideaProject)
	}
	val source = languageLevel.toString()
	val target = targetBytecodeVersion.toString()
	return JavaCompilerSettings(sourceCompatibility = source, targetCompatibility = target)
}

private fun createCompilerSettings(ideaProject: IdeaProject): Common.JavaCompilerSettings {
	val settings = ideaProject.javaLanguageSettings ?: return DEFAULT_COMPILER_SETTINGS
	val source = settings.languageLevel
	val target = settings.targetBytecodeVersion
	return if (source == null || target == null) {
		DEFAULT_COMPILER_SETTINGS
	} else {
		JavaCompilerSettings(
			sourceCompatibility = source.toString(),
			targetCompatibility = target.toString(),
		)
	}
}

fun IdeaDependency.asProtoModel(moduleNameToPath: Map<String, String>) =
	JavaDependency(
		jarFilePath = (this as? IdeaSingleEntryLibraryDependency)?.file?.absolutePath,
		scope = this.scope.scope,
		exported = this.exported,
		externalLibrary = (this as? IdeaSingleEntryLibraryDependency)?.asProtoModel(),
		module = (this as? IdeaModuleDependency)?.asProtoModel(moduleNameToPath),
	)

fun IdeaSingleEntryLibraryDependency.asProtoModel() =
	JavaExternalLibraryDependency(
		libraryInfo =
			this.gradleModuleVersion?.let { moduleVersion ->
				LibraryInfo(
					group = moduleVersion.group,
					name = moduleVersion.name,
					version = moduleVersion.version,
				)
			},
	)

fun IdeaModuleDependency.asProtoModel(moduleNameToPath: Map<String, String>) =
	JavaModuleDependency(
		moduleName = this.targetModuleName,
		projectPath = moduleNameToPath[this.targetModuleName] ?: "",
	)

fun IdeaContentRoot.asProtoModel() =
	JavaContentRoot(
		sourceDirectoryList = sourceDirectories.map { it.asProtoModel() },
		testDirectoryList = testDirectories.map { it.asProtoModel() },
	)

fun IdeaSourceDirectory.asProtoModel() =
	JavaSourceDirectory(
		directoryPath = directory.absolutePath,
		isGenerated = isGenerated,
	)
