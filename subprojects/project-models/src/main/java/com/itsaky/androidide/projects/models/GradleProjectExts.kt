package com.itsaky.androidide.projects.models

import com.itsaky.androidide.project.GradleModels
import java.io.File

val GradleModels.GradleProject.projectDir: File
	get() = File(projectDirPath)

val GradleModels.GradleProject.buildDir: File
	get() = File(buildDirPath)
