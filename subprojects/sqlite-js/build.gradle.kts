import com.itsaky.androidide.build.config.BuildConfig

plugins {
	id("com.android.library")
}

description = "sqlite-js for apps built with Code on the Go: JavaScript functions in SQLite (ADFA-7082)"

android {
	namespace = "org.appdevforall.sqlitejs"
	ndkVersion = BuildConfig.NDK_VERSION

	defaultConfig {
		// upstream's AAR needs 26 too; an app built with CoGo that uses this needs at least it
		minSdk = 26
		// One AAR for both ABIs of the phones apps built with CoGo run on, unlike CoGo's own
		// modules: this module has no v7/v8 flavors (AndroidModuleConf).
		ndk {
			abiFilters.clear()
			abiFilters += listOf("arm64-v8a", "armeabi-v7a")
		}
		externalNativeBuild {
			cmake {
				arguments += "-DANDROID_STL=none"
			}
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
	}

	buildFeatures {
		viewBinding = false
		buildConfig = false
	}

	externalNativeBuild {
		cmake {
			path = file("src/main/cpp/CMakeLists.txt")
			version = "3.31.0+"
		}
	}
}

// The AAR the template that uses sqlite-js builds against, published by release-sqlite-js.yml
tasks.register<Copy>("createSqliteJsAar") {
	dependsOn("assembleRelease")
	from(layout.buildDirectory.file("outputs/aar/sqlite-js-release.aar"))
	into(layout.buildDirectory.dir("libs"))
	rename { "sqlite-js.aar" }
}
