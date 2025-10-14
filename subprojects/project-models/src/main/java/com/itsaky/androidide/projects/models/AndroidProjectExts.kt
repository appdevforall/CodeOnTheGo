package com.itsaky.androidide.projects.models

import com.itsaky.androidide.project.AndroidModels
import java.io.File

val AndroidModels.AndroidProjectOrBuilder.classesJar: File?
	get() = if (hasClassesJarPath()) File(classesJarPath) else null

val AndroidModels.AndroidProjectOrBuilder.bootClassPaths: List<File>
	get() = bootClassPathsList.map { File(it) }

val AndroidModels.AndroidArtifactOrBuilder.generatedSourceFolders: List<File>
	get() = generatedSourceFolderPathsList.map { File(it) }

val AndroidModels.AndroidArtifactOrBuilder.classJars: List<File>
	get() = classJarPathsList.map { File(it) }

val AndroidModels.AndroidArtifactOrBuilder.assembleTaskOutputListingFile: File
	get() = File(assembleTaskOutputListingFilePath)

val AndroidModels.SourceProviderOrBuilder.resDirs: List<File>
	get() = resDirsList.map { File(it) }

val AndroidModels.SourceProviderOrBuilder.javaDirs: List<File>
	get() = javaDirsList.map { File(it) }

val AndroidModels.SourceProviderOrBuilder.kotlinDirs: List<File>
	get() = javaDirsList.map { File(it) }

val AndroidModels.AndroidLibraryDataOrBuilder.compileJarFiles: List<File>
	get() = compileJarFilePathsList.map { File(it) }

val AndroidModels.AndroidLibraryDataOrBuilder.resFolder: File
	get() = File(resFolderPath)

val AndroidModels.AndroidLibraryDataOrBuilder.manifestFile: File
	get() = File(manifestFilePath)

val AndroidModels.LibraryOrBuilder.artifact: File
	get() = File(artifactPath)
