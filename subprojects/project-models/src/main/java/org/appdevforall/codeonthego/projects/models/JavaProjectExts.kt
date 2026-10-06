package org.appdevforall.codeonthego.projects.models

import org.appdevforall.codeonthego.project.JavaModels
import java.io.File

val JavaModels.JavaSourceDirectoryOrBuilder.directory: File
	get() = File(directoryPath)

val JavaModels.JavaDependencyOrBuilder.jarFile: File
	get() = File(jarFilePath)
