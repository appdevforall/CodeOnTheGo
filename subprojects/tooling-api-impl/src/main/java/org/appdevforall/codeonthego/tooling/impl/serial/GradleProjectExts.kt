package org.appdevforall.codeonthego.tooling.impl.serial

import org.appdevforall.codeonthego.project.AndroidModels
import org.appdevforall.codeonthego.project.GradleModels
import org.appdevforall.codeonthego.project.GradleProject
import org.appdevforall.codeonthego.project.GradleTask
import org.appdevforall.codeonthego.project.JavaModels
import org.gradle.tooling.model.GradleProject
import org.gradle.tooling.model.GradleTask

fun GradleProject.asProtoModel(
	androidProject: AndroidModels.AndroidProject? = null,
	javaProject: JavaModels.JavaProject? = null,
): GradleModels.GradleProject =
	GradleProject(
		name = this.name,
		description = this.description,
		path = this.path,
		projectDirPath = projectDirectory.absolutePath,
		buildDirPath = buildDirectory.absolutePath,
		buildScriptPath = buildScript.sourceFile.absolutePath,
		taskList = tasks.map { task -> task.asProtoModel() },
		androidProject = androidProject,
		javaProject = javaProject,
	)

fun GradleTask.asProtoModel() =
	GradleTask(
		name = this.name,
		path = this.path,
		isPublic = this.isPublic,
		group = this.group,
		description = this.description,
		displayName = this.displayName,
		projectPath = this.project.path,
	)
