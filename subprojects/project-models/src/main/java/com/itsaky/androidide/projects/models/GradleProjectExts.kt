package com.itsaky.androidide.projects.models

import com.itsaky.androidide.project.GradleModels
import java.io.File

val GradleModels.GradleProjectOrBuilder.projectDir: File
	get() = File(projectDirPath)

val GradleModels.GradleProjectOrBuilder.buildDir: File
	get() = File(buildDirPath)
