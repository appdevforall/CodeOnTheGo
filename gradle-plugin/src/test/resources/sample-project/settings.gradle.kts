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
	// Dependency repos for functional tests that run a real `assemble` (the Quick Build
	// proxy app build config-cache test resolves the app's androidx deps here). Tests that only
	// run `:app:tasks` never resolve a classpath, so this is inert for them.
	repositories {
		google()
		mavenCentral()
	}
}

rootProject.name = "Sample App"
include(":app")
include(":nested:app")
