plugins {
	id("java-library")
	alias(libs.plugins.kotlin.jvm)
}

dependencies {
	api(projects.subprojects.builderModelImpl)
	api(projects.subprojects.projectModels)
}
