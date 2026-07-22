pluginManagement {
	// COTGSettingsPlugin adds the IDE's local repos here, which drops Gradle's implicit
	// gradlePluginPortal() default - so the fixture has to name its own plugin repos.
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
}

rootProject.name = "Sample App"
include(":app")
include(":nested:app")
