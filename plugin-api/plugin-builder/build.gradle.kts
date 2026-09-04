plugins {
	`kotlin-dsl`
	`maven-publish`
}

group = "com.itsaky.androidide.plugins"
version = "1.0.0"

dependencies {
	// AGP is provided at runtime by the plugin project's own `com.android.application`,
	// and on-device plugin builds use the tooling AGP (`agp-tooling`), which is what
	// the harvested localMvnRepository ships. Keep it compileOnly so the published
	// POM stays dependency-free: forcing it as a transitive would make the coordinate
	// unresolvable offline whenever the harvested AGP differs from a pinned version.
	compileOnly(libs.tooling.agp)
}

gradlePlugin {
	plugins {
		create("pluginBuilder") {
			id = "com.itsaky.androidide.plugins.build"
			implementationClass = "com.itsaky.androidide.plugins.build.PluginBuilder"
			displayName = "Code on the Go Plugin Builder"
			description = "Gradle plugin for building Code on the Go plugins"
		}
	}
}

publishing {
	repositories {
		maven {
			name = "pluginMavenRepo"
			url = uri(layout.buildDirectory.dir("plugin-maven-repo"))
		}
	}
}

// Ship POMs only (parity with the harvested repo); marker/plugin resolution works off POMs.
tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
	compilerOptions {
		apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
		languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
	}
}
