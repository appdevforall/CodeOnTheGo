package org.appdevforall.codeonthego.projects.models

import org.appdevforall.codeonthego.project.GradleModels
import java.io.File

val GradleModels.GradleProjectOrBuilder.projectDir: File
	get() = File(projectDirPath)

val GradleModels.GradleProjectOrBuilder.buildDir: File
	get() = File(buildDirPath)
