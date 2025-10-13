plugins {
	id("java-library")
	alias(libs.plugins.kotlin.jvm)
}

dependencies {
	api(libs.common.kotlin.coroutines.core)

	api(projects.logger)
	api(projects.subprojects.projectModels)
}
