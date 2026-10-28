package com.itsaky.androidide.lsp.kotlin.fixtures

import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.Common
import com.itsaky.androidide.project.GradleModels
import com.itsaky.androidide.project.JavaModels
import com.itsaky.androidide.projects.api.JavaModule
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Builds a minimal Gradle project model at [path], an Android library when [bootClassPath] is set,
 * a Java project with [compileJars] on its compile classpath (in order) otherwise.
 */
internal fun gradleProject(
	path: String,
	bootClassPath: Path? = null,
	compileJars: List<Path> = emptyList(),
	jvmTarget: String? = null,
): GradleModels.GradleProject {
	val dir = "/tmp/collect-kt-modules${path.replace(':', '/')}"
	return GradleModels.GradleProject
		.newBuilder()
		.setName(path.trimStart(':'))
		.setPath(path)
		.setProjectDirPath(dir)
		.setBuildDirPath("$dir/build")
		.setBuildScriptPath("$dir/build.gradle")
		.apply {
			if (bootClassPath != null) {
				setAndroidProject(
					AndroidModels.AndroidProject
						.newBuilder()
						.setProjectType(AndroidModels.ProjectType.LibraryProject)
						.addBootClassPaths(bootClassPath.pathString),
				)
			}
			if (compileJars.isNotEmpty() || jvmTarget != null) {
				setJavaProject(
					JavaModels.JavaProject
						.newBuilder()
						.apply {
							compileJars.forEach { jar ->
								addDependency(
									JavaModels.JavaDependency
										.newBuilder()
										.setJarFilePath(jar.pathString)
										.setScope(JavaModule.SCOPE_COMPILE)
										.setExternalLibrary(JavaModels.JavaExternalLibraryDependency.getDefaultInstance()),
								)
							}
							if (jvmTarget != null) {
								setKotlinCompilerSettings(Common.KotlinCompilerSettings.newBuilder().setJvmTarget(jvmTarget))
							}
						},
				)
			}
		}.build()
}
