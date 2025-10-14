package com.itsaky.androidide.projects.models

import com.itsaky.androidide.project.JavaModels
import java.io.File

val JavaModels.JavaSourceDirectoryOrBuilder.directory: File
	get() = File(directoryPath)

val JavaModels.JavaDependencyOrBuilder.jarFile: File
	get() = File(jarFilePath)